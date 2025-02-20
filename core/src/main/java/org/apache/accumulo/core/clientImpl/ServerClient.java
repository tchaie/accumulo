/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.core.clientImpl;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.accumulo.fate.util.UtilWaitThread.sleepUninterruptibly;

import java.util.ArrayList;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.clientImpl.thrift.ClientService;
import org.apache.accumulo.core.clientImpl.thrift.ClientService.Client;
import org.apache.accumulo.core.clientImpl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.rpc.ThriftClientTypes;
import org.apache.accumulo.core.rpc.ThriftClientTypes.ThriftClientType;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.core.util.ServerServices;
import org.apache.accumulo.core.util.ServerServices.Service;
import org.apache.accumulo.fate.zookeeper.ServiceLock;
import org.apache.accumulo.fate.zookeeper.ZooCache;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TServiceClient;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerClient {
  private static final Logger log = LoggerFactory.getLogger(ServerClient.class);

  public static <T> T execute(ClientContext context, ClientExecReturn<T,ClientService.Client> exec)
      throws AccumuloException, AccumuloSecurityException {
    return execute(context, ThriftClientTypes.CLIENT, exec);
  }

  public static <CT extends TServiceClient,RT> RT execute(ClientContext context,
      ThriftClientType<CT,?> type, ClientExecReturn<RT,CT> exec)
      throws AccumuloException, AccumuloSecurityException {
    try {
      return executeRaw(context, type, exec);
    } catch (ThriftSecurityException e) {
      throw new AccumuloSecurityException(e.user, e.code, e);
    } catch (AccumuloException e) {
      throw e;
    } catch (Exception e) {
      throw new AccumuloException(e);
    }
  }

  public static void executeVoid(ClientContext context, ClientExec<ClientService.Client> exec)
      throws AccumuloException, AccumuloSecurityException {
    try {
      executeRawVoid(context, exec);
    } catch (ThriftSecurityException e) {
      throw new AccumuloSecurityException(e.user, e.code, e);
    } catch (AccumuloException e) {
      throw e;
    } catch (Exception e) {
      throw new AccumuloException(e);
    }
  }

  public static <T> T executeRaw(ClientContext context,
      ClientExecReturn<T,ClientService.Client> exec) throws Exception {
    return executeRaw(context, ThriftClientTypes.CLIENT, exec);
  }

  public static <CT extends TServiceClient,RT> RT executeRaw(ClientContext context,
      ThriftClientType<CT,?> type, ClientExecReturn<RT,CT> exec) throws Exception {
    while (true) {
      CT client = null;
      String server = null;
      try {
        Pair<String,CT> pair = ServerClient.getConnection(context, type, true);
        server = pair.getFirst();
        client = pair.getSecond();
        return exec.execute(client);
      } catch (TApplicationException tae) {
        throw new AccumuloServerException(server, tae);
      } catch (TTransportException tte) {
        log.debug("ClientService request failed " + server + ", retrying ... ", tte);
        sleepUninterruptibly(100, MILLISECONDS);
      } finally {
        if (client != null)
          ServerClient.close(client, context);
      }
    }
  }

  public static void executeRawVoid(ClientContext context, ClientExec<ClientService.Client> exec)
      throws Exception {
    while (true) {
      ClientService.Client client = null;
      String server = null;
      try {
        Pair<String,Client> pair =
            ServerClient.getConnection(context, ThriftClientTypes.CLIENT, true);
        server = pair.getFirst();
        client = pair.getSecond();
        exec.execute(client);
        break;
      } catch (TApplicationException tae) {
        throw new AccumuloServerException(server, tae);
      } catch (TTransportException tte) {
        log.debug("ClientService request failed " + server + ", retrying ... ", tte);
        sleepUninterruptibly(100, MILLISECONDS);
      } finally {
        if (client != null)
          ServerClient.close(client, context);
      }
    }
  }

  static volatile boolean warnedAboutTServersBeingDown = false;

  public static <CT extends TServiceClient> Pair<String,CT> getConnection(ClientContext context,
      ThriftClientType<CT,?> type, boolean preferCachedConnections) throws TTransportException {
    checkArgument(context != null, "context is null");
    long rpcTimeout = context.getClientTimeoutInMillis();
    // create list of servers
    ArrayList<ThriftTransportKey> servers = new ArrayList<>();

    // add tservers
    ZooCache zc = context.getZooCache();
    for (String tserver : zc.getChildren(context.getZooKeeperRoot() + Constants.ZTSERVERS)) {
      var zLocPath =
          ServiceLock.path(context.getZooKeeperRoot() + Constants.ZTSERVERS + "/" + tserver);
      byte[] data = zc.getLockData(zLocPath);
      if (data != null) {
        String strData = new String(data, UTF_8);
        if (!strData.equals("manager"))
          servers.add(new ThriftTransportKey(
              new ServerServices(strData).getAddress(Service.TSERV_CLIENT), rpcTimeout, context));
      }
    }

    boolean opened = false;
    try {
      Pair<String,TTransport> pair =
          context.getTransportPool().getAnyTransport(servers, preferCachedConnections);
      CT client = ThriftUtil.createClient(type, pair.getSecond());
      opened = true;
      warnedAboutTServersBeingDown = false;
      return new Pair<>(pair.getFirst(), client);
    } finally {
      if (!opened) {
        if (!warnedAboutTServersBeingDown) {
          if (servers.isEmpty()) {
            log.warn("There are no tablet servers: check that zookeeper and accumulo are running.");
          } else {
            log.warn("Failed to find an available server in the list of servers: {}", servers);
          }
          warnedAboutTServersBeingDown = true;
        }
      }
    }
  }

  public static void close(TServiceClient client, ClientContext context) {
    if (client != null && client.getInputProtocol() != null
        && client.getInputProtocol().getTransport() != null) {
      context.getTransportPool().returnTransport(client.getInputProtocol().getTransport());
    } else {
      log.debug("Attempt to close null connection to a server", new Exception());
    }
  }
}
