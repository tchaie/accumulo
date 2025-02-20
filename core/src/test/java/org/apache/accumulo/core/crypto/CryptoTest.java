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
package org.apache.accumulo.core.crypto;

import static com.google.common.collect.MoreCollectors.onlyElement;
import static org.apache.accumulo.core.conf.Property.INSTANCE_CRYPTO_PREFIX;
import static org.apache.accumulo.core.crypto.CryptoUtils.getFileDecrypter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import org.apache.accumulo.core.classloader.ClassLoaderUtil;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.rfile.RFile;
import org.apache.accumulo.core.client.rfile.RFileWriter;
import org.apache.accumulo.core.client.summary.Summarizer;
import org.apache.accumulo.core.client.summary.SummarizerConfiguration;
import org.apache.accumulo.core.client.summary.Summary;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ConfigurationCopy;
import org.apache.accumulo.core.conf.DefaultConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.crypto.CryptoServiceFactory.ClassloaderType;
import org.apache.accumulo.core.crypto.streams.NoFlushOutputStream;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.spi.crypto.AESCryptoService;
import org.apache.accumulo.core.spi.crypto.CryptoEnvironment;
import org.apache.accumulo.core.spi.crypto.CryptoEnvironment.Scope;
import org.apache.accumulo.core.spi.crypto.CryptoService;
import org.apache.accumulo.core.spi.crypto.CryptoService.CryptoException;
import org.apache.accumulo.core.spi.crypto.FileDecrypter;
import org.apache.accumulo.core.spi.crypto.FileEncrypter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class CryptoTest {

  private static final SecureRandom random = new SecureRandom();
  private static final int MARKER_INT = 0xCADEFEDD;
  private static final String MARKER_STRING = "1 2 3 4 5 6 7 8 a b c d e f g h ";
  private static Configuration hadoopConf = new Configuration();

  public enum ConfigMode {
    CRYPTO_OFF, CRYPTO_ON, CRYPTO_ON_DISABLED
  }

  @BeforeAll
  public static void setupKeyFiles() throws IOException {
    setupKeyFiles(CryptoTest.class);
  }

  public static void setupKeyFiles(Class<?> testClass) throws IOException {
    FileSystem fs = FileSystem.getLocal(hadoopConf);
    Path aesPath = new Path(keyPath(testClass));
    try (FSDataOutputStream out = fs.create(aesPath)) {
      out.writeUTF("sixteenbytekey"); // 14 + 2 from writeUTF
    }
    try (FSDataOutputStream out = fs.create(new Path(emptyKeyPath(testClass)))) {
      // auto close after creating
      assertNotNull(out);
    }
  }

  @SuppressWarnings("fallthrough")
  public static ConfigurationCopy getAccumuloConfig(ConfigMode configMode, Class<?> testClass) {
    ConfigurationCopy cfg = new ConfigurationCopy(DefaultConfiguration.getInstance());
    switch (configMode) {
      case CRYPTO_ON_DISABLED:
        cfg.set(INSTANCE_CRYPTO_PREFIX.getKey() + "enabled", "false");
        // fall through to set remaining config
      case CRYPTO_ON:
        cfg.set(Property.INSTANCE_CRYPTO_SERVICE,
            "org.apache.accumulo.core.spi.crypto.AESCryptoService");
        cfg.set(INSTANCE_CRYPTO_PREFIX.getKey() + "key.uri", CryptoTest.keyPath(testClass));
        break;
      case CRYPTO_OFF:
        break;
    }
    return cfg;
  }

  private ConfigurationCopy getAccumuloConfig(ConfigMode configMode) {
    return getAccumuloConfig(configMode, getClass());
  }

  public static String keyPath(Class<?> testClass) {
    return System.getProperty("user.dir") + "/target/" + testClass.getSimpleName() + "-testkeyfile";
  }

  public static String emptyKeyPath(Class<?> testClass) {
    return System.getProperty("user.dir") + "/target/" + testClass.getSimpleName()
        + "-emptykeyfile";
  }

  @Test
  public void simpleGCMTest() throws Exception {
    AccumuloConfiguration conf = getAccumuloConfig(ConfigMode.CRYPTO_ON);

    CryptoService cs = new AESCryptoService();
    cs.init(conf.getAllPropertiesWithPrefix(Property.INSTANCE_CRYPTO_PREFIX));
    CryptoEnvironment encEnv = new CryptoEnvironmentImpl(Scope.RFILE, null);
    FileEncrypter encrypter = cs.getFileEncrypter(encEnv);
    byte[] params = encrypter.getDecryptionParameters();
    assertNotNull(params);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);
    CryptoUtils.writeParams(params, dataOut);
    OutputStream encrypted = encrypter.encryptStream(dataOut);

    assertNotNull(encrypted);
    DataOutputStream cipherOut = new DataOutputStream(encrypted);

    cipherOut.writeUTF(MARKER_STRING);

    cipherOut.close();
    dataOut.close();
    encrypted.close();
    out.close();

    byte[] cipherText = out.toByteArray();

    // decrypt
    ByteArrayInputStream in = new ByteArrayInputStream(cipherText);
    FileDecrypter decrypter = getFileDecrypter(cs, Scope.RFILE, new DataInputStream(in));
    DataInputStream decrypted = new DataInputStream(decrypter.decryptStream(in));
    String plainText = decrypted.readUTF();
    decrypted.close();
    in.close();

    assertEquals(MARKER_STRING, new String(plainText));
  }

  @Test
  public void testAESCryptoServiceWAL() throws Exception {
    AESCryptoService cs = new AESCryptoService();
    byte[] resultingBytes = encrypt(cs, Scope.WAL, ConfigMode.CRYPTO_ON);

    String stringifiedBytes = Arrays.toString(resultingBytes);
    String stringifiedMarkerBytes = getStringifiedBytes(null, MARKER_STRING, MARKER_INT);

    assertNotEquals(stringifiedBytes, stringifiedMarkerBytes);

    decrypt(resultingBytes, Scope.WAL, ConfigMode.CRYPTO_ON);
  }

  /**
   * AESCryptoService is configured but only for reading
   */
  @Test
  public void testAESCryptoServiceWALDisabled() throws Exception {
    AESCryptoService cs = new AESCryptoService();
    // make sure we can read encrypted
    byte[] encryptedBytes = encrypt(cs, Scope.WAL, ConfigMode.CRYPTO_ON);
    String stringEncryptedBytes = Arrays.toString(encryptedBytes);
    String stringifiedMarkerBytes = getStringifiedBytes(null, MARKER_STRING, MARKER_INT);
    assertNotEquals(stringEncryptedBytes, stringifiedMarkerBytes);
    decrypt(encryptedBytes, Scope.WAL, ConfigMode.CRYPTO_ON_DISABLED);

    // make sure we don't encrypt when disabled
    byte[] plainBytes = encrypt(cs, Scope.WAL, ConfigMode.CRYPTO_ON_DISABLED);
    String stringPlainBytes = Arrays.toString(plainBytes);
    assertNotEquals(stringEncryptedBytes, stringPlainBytes);
    decrypt(plainBytes, Scope.WAL, ConfigMode.CRYPTO_ON_DISABLED);
  }

  @Test
  public void testAESCryptoServiceRFILE() throws Exception {
    AESCryptoService cs = new AESCryptoService();
    byte[] resultingBytes = encrypt(cs, Scope.RFILE, ConfigMode.CRYPTO_ON);

    String stringifiedBytes = Arrays.toString(resultingBytes);
    String stringifiedMarkerBytes = getStringifiedBytes(null, MARKER_STRING, MARKER_INT);

    assertNotEquals(stringifiedBytes, stringifiedMarkerBytes);

    decrypt(resultingBytes, Scope.RFILE, ConfigMode.CRYPTO_ON);
  }

  /**
   * AESCryptoService is configured but only for reading
   */
  @Test
  public void testAESCryptoServiceRFILEDisabled() throws Exception {
    AESCryptoService cs = new AESCryptoService();
    // make sure we can read encrypted
    byte[] encryptedBytes = encrypt(cs, Scope.RFILE, ConfigMode.CRYPTO_ON);
    String stringEncryptedBytes = Arrays.toString(encryptedBytes);
    String stringifiedMarkerBytes = getStringifiedBytes(null, MARKER_STRING, MARKER_INT);
    assertNotEquals(stringEncryptedBytes, stringifiedMarkerBytes);
    decrypt(encryptedBytes, Scope.RFILE, ConfigMode.CRYPTO_ON_DISABLED);

    // make sure we don't encrypt when disabled
    byte[] plainBytes = encrypt(cs, Scope.RFILE, ConfigMode.CRYPTO_ON_DISABLED);
    String stringPlainBytes = Arrays.toString(plainBytes);
    assertNotEquals(stringEncryptedBytes, stringPlainBytes);
    decrypt(plainBytes, Scope.RFILE, ConfigMode.CRYPTO_ON_DISABLED);
  }

  @Test
  public void testNoEncryptionWAL() throws Exception {
    CryptoService cs = CryptoServiceFactory.newDefaultInstance();
    byte[] encryptedBytes = encrypt(cs, Scope.WAL, ConfigMode.CRYPTO_OFF);

    String stringifiedBytes = Arrays.toString(encryptedBytes);
    String stringifiedMarkerBytes =
        getStringifiedBytes("U+1F47B".getBytes(), MARKER_STRING, MARKER_INT);

    assertEquals(stringifiedBytes, stringifiedMarkerBytes);

    decrypt(encryptedBytes, Scope.WAL, ConfigMode.CRYPTO_OFF);
  }

  @Test
  public void testNoEncryptionRFILE() throws Exception {
    CryptoService cs = CryptoServiceFactory.newDefaultInstance();
    byte[] encryptedBytes = encrypt(cs, Scope.RFILE, ConfigMode.CRYPTO_OFF);

    String stringifiedBytes = Arrays.toString(encryptedBytes);
    String stringifiedMarkerBytes =
        getStringifiedBytes("U+1F47B".getBytes(), MARKER_STRING, MARKER_INT);

    assertEquals(stringifiedBytes, stringifiedMarkerBytes);

    decrypt(encryptedBytes, Scope.RFILE, ConfigMode.CRYPTO_OFF);
  }

  @Test
  public void testRFileEncrypted() throws Exception {
    AccumuloConfiguration cryptoOnConf = getAccumuloConfig(ConfigMode.CRYPTO_ON);
    FileSystem fs = FileSystem.getLocal(hadoopConf);
    ArrayList<Key> keys = testData();
    SummarizerConfiguration sumConf =
        SummarizerConfiguration.builder(KeyCounter.class.getName()).build();

    String file = "target/testFile1.rf";
    fs.delete(new Path(file), true);
    try (RFileWriter writer = RFile.newWriter().to(file).withFileSystem(fs)
        .withTableProperties(cryptoOnConf).withSummarizers(sumConf).build()) {
      Value empty = new Value();
      writer.startDefaultLocalityGroup();
      for (Key key : keys) {
        writer.append(key, empty);
      }
    }

    Scanner iter =
        RFile.newScanner().from(file).withFileSystem(fs).withTableProperties(cryptoOnConf).build();
    ArrayList<Key> keysRead = new ArrayList<>();
    iter.forEach(e -> keysRead.add(e.getKey()));
    assertEquals(keys, keysRead);

    Collection<Summary> summaries =
        RFile.summaries().from(file).withFileSystem(fs).withTableProperties(cryptoOnConf).read();
    Summary summary = summaries.stream().collect(onlyElement());
    assertEquals(keys.size(), (long) summary.getStatistics().get("keys"));
    assertEquals(1, summary.getStatistics().size());
    assertEquals(0, summary.getFileStatistics().getInaccurate());
    assertEquals(1, summary.getFileStatistics().getTotal());

  }

  @Test
  // This test is to ensure when Crypto is configured that it can read unencrypted files
  public void testReadNoCryptoWithCryptoConfigured() throws Exception {
    AccumuloConfiguration cryptoOffConf = getAccumuloConfig(ConfigMode.CRYPTO_OFF);
    AccumuloConfiguration cryptoOnConf = getAccumuloConfig(ConfigMode.CRYPTO_ON);
    FileSystem fs = FileSystem.getLocal(hadoopConf);
    ArrayList<Key> keys = testData();

    String file = "target/testFile2.rf";
    fs.delete(new Path(file), true);
    try (RFileWriter writer =
        RFile.newWriter().to(file).withFileSystem(fs).withTableProperties(cryptoOffConf).build()) {
      Value empty = new Value();
      writer.startDefaultLocalityGroup();
      for (Key key : keys) {
        writer.append(key, empty);
      }
    }

    Scanner iter =
        RFile.newScanner().from(file).withFileSystem(fs).withTableProperties(cryptoOnConf).build();
    ArrayList<Key> keysRead = new ArrayList<>();
    iter.forEach(e -> keysRead.add(e.getKey()));
    assertEquals(keys, keysRead);
  }

  @Test
  public void testMissingConfigProperties() throws ReflectiveOperationException {
    ConfigurationCopy aconf = new ConfigurationCopy(DefaultConfiguration.getInstance());
    Configuration conf = new Configuration(false);
    for (Map.Entry<String,String> e : conf) {
      aconf.set(e.getKey(), e.getValue());
    }
    aconf.set(Property.INSTANCE_CRYPTO_SERVICE,
        "org.apache.accumulo.core.spi.crypto.AESCryptoService");
    String configuredClass = aconf.get(Property.INSTANCE_CRYPTO_SERVICE.getKey());
    Class<? extends CryptoService> clazz =
        ClassLoaderUtil.loadClass(configuredClass, CryptoService.class);
    CryptoService cs = clazz.getDeclaredConstructor().newInstance();

    assertEquals(AESCryptoService.class, cs.getClass());
    assertThrows(NullPointerException.class,
        () -> cs.init(aconf.getAllPropertiesWithPrefix(Property.TABLE_PREFIX)));
  }

  @Test
  public void testAESKeyUtilsGeneratesKey() throws NoSuchAlgorithmException,
      NoSuchProviderException, NoSuchPaddingException, InvalidKeyException {
    // verify valid key sizes (corresponds to 128, 192, and 256 bits)
    for (int i : new int[] {16, 24, 32}) {
      verifyKeySizeForCBC(random, i);
    }
    // verify invalid key sizes
    for (int i : new int[] {1, 2, 8, 11, 15, 64, 128}) {
      assertThrows(InvalidKeyException.class, () -> verifyKeySizeForCBC(random, i));
    }
  }

  // this has to be a separate method, for spotbugs, because spotbugs annotation doesn't seem to
  // apply to the lambda inline
  @SuppressFBWarnings(value = "CIPHER_INTEGRITY", justification = "CBC is being tested")
  private void verifyKeySizeForCBC(SecureRandom sr, int sizeInBytes)
      throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException {
    java.security.Key key = AESCryptoService.generateKey(sr, sizeInBytes);
    Cipher.getInstance("AES/CBC/NoPadding").init(Cipher.ENCRYPT_MODE, key);
  }

  @Test
  public void testAESKeyUtilsWrapAndUnwrap()
      throws NoSuchAlgorithmException, NoSuchProviderException {
    java.security.Key kek = AESCryptoService.generateKey(random, 16);
    java.security.Key fek = AESCryptoService.generateKey(random, 16);
    byte[] wrapped = AESCryptoService.wrapKey(fek, kek);
    assertFalse(Arrays.equals(fek.getEncoded(), wrapped));
    java.security.Key unwrapped = AESCryptoService.unwrapKey(wrapped, kek);
    assertEquals(unwrapped, fek);
  }

  @Test
  public void testAESKeyUtilsFailUnwrapWithWrongKEK()
      throws NoSuchAlgorithmException, NoSuchProviderException {
    java.security.Key kek = AESCryptoService.generateKey(random, 16);
    java.security.Key fek = AESCryptoService.generateKey(random, 16);
    byte[] wrongBytes = kek.getEncoded();
    wrongBytes[0]++;
    java.security.Key wrongKek = new SecretKeySpec(wrongBytes, "AES");

    byte[] wrapped = AESCryptoService.wrapKey(fek, kek);
    assertThrows(CryptoException.class, () -> AESCryptoService.unwrapKey(wrapped, wrongKek));
  }

  @Test
  public void testAESKeyUtilsLoadKekFromUri() throws IOException {
    java.security.Key fileKey = AESCryptoService.loadKekFromUri(keyPath(getClass()));
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    dos.writeUTF("sixteenbytekey");
    SecretKeySpec handKey = new SecretKeySpec(baos.toByteArray(), "AES");
    assertEquals(fileKey, handKey);
  }

  @Test
  public void testAESKeyUtilsLoadKekFromUriInvalidUri() {
    assertThrows(CryptoException.class, () -> AESCryptoService.loadKekFromUri(
        System.getProperty("user.dir") + "/target/CryptoTest-testkeyfile-doesnt-exist"));
  }

  @Test
  public void testAESKeyUtilsLoadKekFromEmptyFile() {
    assertThrows(CryptoException.class,
        () -> AESCryptoService.loadKekFromUri(emptyKeyPath(getClass())));
  }

  private ArrayList<Key> testData() {
    ArrayList<Key> keys = new ArrayList<>();
    keys.add(new Key("a", "cf", "cq"));
    keys.add(new Key("a1", "cf", "cq"));
    keys.add(new Key("a2", "cf", "cq"));
    keys.add(new Key("a3", "cf", "cq"));
    return keys;
  }

  private <C extends CryptoService> byte[] encrypt(C cs, Scope scope, ConfigMode configMode)
      throws Exception {
    AccumuloConfiguration conf = getAccumuloConfig(configMode);
    cs.init(conf.getAllPropertiesWithPrefix(Property.INSTANCE_CRYPTO_PREFIX));
    CryptoEnvironmentImpl env = new CryptoEnvironmentImpl(scope, null);
    FileEncrypter encrypter = cs.getFileEncrypter(env);
    byte[] params = encrypter.getDecryptionParameters();

    assertNotNull(encrypter, "CryptoService returned null FileEncrypter");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);
    CryptoUtils.writeParams(params, dataOut);
    DataOutputStream encrypted =
        new DataOutputStream(encrypter.encryptStream(new NoFlushOutputStream(dataOut)));
    assertNotNull(encrypted);

    encrypted.writeUTF(MARKER_STRING);
    encrypted.writeInt(MARKER_INT);
    encrypted.close();
    dataOut.close();
    out.close();
    return out.toByteArray();
  }

  private void decrypt(byte[] resultingBytes, Scope scope, ConfigMode configMode) throws Exception {
    try (DataInputStream dataIn = new DataInputStream(new ByteArrayInputStream(resultingBytes))) {
      AccumuloConfiguration conf = getAccumuloConfig(configMode);
      CryptoService cs = CryptoServiceFactory.newInstance(conf, ClassloaderType.JAVA);
      FileDecrypter decrypter = getFileDecrypter(cs, scope, dataIn);

      try (DataInputStream decrypted = new DataInputStream(decrypter.decryptStream(dataIn))) {
        String markerString = decrypted.readUTF();
        int markerInt = decrypted.readInt();

        assertEquals(MARKER_STRING, markerString);
        assertEquals(MARKER_INT, markerInt);
      }
    }
  }

  private String getStringifiedBytes(byte[] params, String s, int i) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    if (params != null) {
      dataOut.writeInt(params.length);
      dataOut.write(params);
    }
    dataOut.writeUTF(s);
    dataOut.writeInt(i);
    dataOut.close();
    byte[] stringMarkerBytes = out.toByteArray();
    return Arrays.toString(stringMarkerBytes);
  }

  // simple counter to just make sure crypto works with summaries
  public static class KeyCounter implements Summarizer {
    @Override
    public Collector collector(SummarizerConfiguration sc) {
      return new Collector() {

        long keys = 0;

        @Override
        public void accept(Key k, Value v) {
          if (!k.isDeleted())
            keys++;
        }

        @Override
        public void summarize(StatisticConsumer sc) {
          sc.accept("keys", keys);
        }
      };
    }

    @Override
    public Combiner combiner(SummarizerConfiguration sc) {
      return (m1, m2) -> m2.forEach((k, v) -> m1.merge(k, v, Long::sum));
    }
  }

}
