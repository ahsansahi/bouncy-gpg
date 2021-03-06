/*
 * Copyright 2018 Paul Schaub.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.generation;


import static java.util.Objects.requireNonNull;
import static name.neuhalfen.projects.crypto.internal.Preconditions.checkArgument;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.annotation.Nullable;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.algorithms.PGPHashAlgorithms;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.callbacks.KeyringConfigCallbacks;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.generation.internal.KeyRingSubKeyFixUtil;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.generation.type.ECDHKeyType;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.generation.type.ECDSAKeyType;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.generation.type.KeyType;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.generation.type.RSAKeyType;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.generation.type.curve.EllipticCurve;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.generation.type.length.RsaLength;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.keyrings.InMemoryKeyring;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.keyrings.KeyringConfig;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.keyrings.KeyringConfigs;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
import org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;

@SuppressWarnings({"PMD.LawOfDemeter", "PMD.ExcessiveImports"})
public class KeyRingBuilderImpl implements KeyRingBuilder, SimpleKeyRingBuilder {

  private final static Charset UTF_8 = Charset.forName("UTF-8");

  private final List<KeySpec> keySpecs = new ArrayList<>();
  private String userId;
  private Passphrase passphrase;

  @Override
  public KeyringConfig simpleRsaKeyRing(String userId, RsaLength length)
      throws PGPException, NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException, IOException {
    requireNonNull(userId, "userId must not be null");
    requireNonNull(length, "length must not be null");

    return withSubKey(
        KeySpec.getBuilder(RSAKeyType.withLength(length))
            .allowKeyToBeUsedTo(KeyFlag.ENCRYPT_STORAGE, KeyFlag.ENCRYPT_COMMS)
            .withDefaultAlgorithms())
        .withSubKey(
            KeySpec.getBuilder(RSAKeyType.withLength(length))
                .allowKeyToBeUsedTo(KeyFlag.AUTHENTICATION)
                .withDefaultAlgorithms())
        .withMasterKey(KeySpec.getBuilder(RSAKeyType.withLength(length))
            .allowKeyToBeUsedTo(KeyFlag.CERTIFY_OTHER, KeyFlag.SIGN_DATA)
            .withDefaultAlgorithms())
        .withPrimaryUserId(userId)
        .withoutPassphrase()
        .build();
  }

  @Override
  public KeyringConfig simpleEccKeyRing(String userId)
      throws PGPException, NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException, IOException {
    requireNonNull(userId, "userId must not be null");

    return withSubKey(
        KeySpec.getBuilder(ECDHKeyType.fromCurve(EllipticCurve.CURVE_NIST_P256))
            .allowKeyToBeUsedTo(KeyFlag.ENCRYPT_STORAGE, KeyFlag.ENCRYPT_COMMS)
            .withDefaultAlgorithms())
        .withSubKey(KeySpec.getBuilder(ECDHKeyType.fromCurve(EllipticCurve.CURVE_NIST_P256))
            .allowKeyToBeUsedTo(KeyFlag.AUTHENTICATION)
            .withDefaultAlgorithms())
        .withMasterKey(
            KeySpec.getBuilder(ECDSAKeyType.fromCurve(EllipticCurve.CURVE_NIST_P256))
                .allowKeyToBeUsedTo(KeyFlag.CERTIFY_OTHER, KeyFlag.SIGN_DATA)
                .withDefaultAlgorithms())
        .withPrimaryUserId(userId)
        .withoutPassphrase()
        .build();
  }

  @Override
  public KeyRingBuilder withSubKey(KeySpec type) {
    requireNonNull(type, "type must not be null");
    keySpecs.add(type);
    return this;
  }

  @Override
  public WithPrimaryUserId withMasterKey(KeySpec spec) {
    requireNonNull(spec, "spec must not be null");
    checkArgument((spec.getSubpackets().getKeyFlags() & KeyFlags.CERTIFY_OTHER) != 0,
        "Certification Key MUST have KeyFlag CERTIFY_OTHER)");

    keySpecs.add(0, spec);
    return new WithPrimaryUserIdImpl();
  }

  class WithPrimaryUserIdImpl implements WithPrimaryUserId {

    @Override
    public WithPassphrase withPrimaryUserId(String userId) {
      requireNonNull(userId, "userId must not be null");

      KeyRingBuilderImpl.this.userId = userId;
      return new WithPassphraseImpl();
    }

    @Override
    public WithPassphrase withPrimaryUserId(byte[] userId) {
      requireNonNull(userId, "userId must not be null");
      checkArgument(userId.length > 0, "userId mus have length >0");

      return withPrimaryUserId(new String(userId, UTF_8));
    }
  }

  class WithPassphraseImpl implements WithPassphrase {

    @Override
    public Build withPassphrase(Passphrase passphrase) {
      requireNonNull(passphrase, "passphrase must not be null");
      KeyRingBuilderImpl.this.passphrase = passphrase;
      return new BuildImpl();
    }

    @Override
    public Build withoutPassphrase() {
      KeyRingBuilderImpl.this.passphrase = Passphrase.emptyPassphrase();
      return new BuildImpl();
    }

    private class BuildImpl implements Build {

      @Override
      public KeyringConfig build()
          throws NoSuchAlgorithmException, PGPException, NoSuchProviderException,
          InvalidAlgorithmParameterException, IOException {

        // Hash Calculator
        final PGPDigestCalculator calculator = new JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build()
            .get(PGPHashAlgorithms.SHA1.getAlgorithmId());

        // Encryptor for encrypting secret keys
        final boolean withPassphrase = !passphrase.isEmpty();

        @Nullable final PBESecretKeyEncryptor encryptor;
        if (withPassphrase) {
          // AES-256 encrypted
          encryptor = new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, calculator)
              .setProvider(BouncyCastleProvider.PROVIDER_NAME)
              .build(passphrase.getChars());
        } else {
          // unencrypted key pair
          encryptor = null;
        }

        // First key is the Master Key
        final KeySpec certKeySpec = keySpecs.get(0);
        // Remove master key, so that we later only add sub keys.
        keySpecs.remove(0);

        // Generate Master Key
        final PGPKeyPair certKey = generateKeyPair(certKeySpec);

        // Signer for creating self-signature
        final PGPContentSignerBuilder signer = new JcaPGPContentSignerBuilder(
            certKey.getPublicKey().getAlgorithm(), PGPHashAlgorithms.SHA_512.getAlgorithmId())
            .setProvider(BouncyCastleProvider.PROVIDER_NAME);

        final PGPSignatureSubpacketVector hashedSubPackets = certKeySpec.getSubpackets();

        // Generator which the user can get the key pair from
        final PGPKeyRingGenerator ringGenerator = new PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION, certKey,
            userId, calculator,
            hashedSubPackets, null, signer, encryptor);

        for (final KeySpec subKeySpec : keySpecs) {
          final PGPKeyPair subKey = generateKeyPair(subKeySpec);
          if (subKeySpec.isInheritedSubPackets()) {
            ringGenerator.addSubKey(subKey);
          } else {
            ringGenerator.addSubKey(subKey, subKeySpec.getSubpackets(), null);
          }
        }

        final PGPPublicKeyRing publicKeys = ringGenerator.generatePublicKeyRing();
        PGPSecretKeyRing secretKeys = ringGenerator.generateSecretKeyRing();

        // TODO: Remove once BC 1.61 is released

        final PBESecretKeyDecryptor decryptor;
        if (withPassphrase) {
          // AES-256 encrypted
          decryptor = new JcePBESecretKeyDecryptorBuilder(
              new JcaPGPDigestCalculatorProviderBuilder()
                  .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                  .build()).build(passphrase.getChars());

        } else {
          // unencrypted key pair
          decryptor = null;
        }

        secretKeys = KeyRingSubKeyFixUtil.repairSubkeyPackets(secretKeys, decryptor, encryptor);

        final InMemoryKeyring keyring;
        if (passphrase.isEmpty()) {
          keyring = KeyringConfigs
              .forGpgExportedKeys(KeyringConfigCallbacks.withUnprotectedKeys());
        } else {
          keyring = KeyringConfigs
              .forGpgExportedKeys(KeyringConfigCallbacks.withPassword(passphrase.getChars()));
        }

        keyring.addSecretKeyRing(secretKeys);
        keyring.addPublicKeyRing(publicKeys);
        passphrase.clear();

        return keyring;
      }

      private PGPKeyPair generateKeyPair(KeySpec spec)
          throws NoSuchProviderException, NoSuchAlgorithmException, PGPException,
          InvalidAlgorithmParameterException {

        final KeyType type = spec.getKeyType();
        final KeyPairGenerator certKeyGenerator = KeyPairGenerator.getInstance(
            type.getName(), BouncyCastleProvider.PROVIDER_NAME);
        certKeyGenerator.initialize(type.getAlgorithmSpec());

        // Create raw Key Pair
        final KeyPair keyPair = certKeyGenerator.generateKeyPair();

        // Form PGP key pair
        return new JcaPGPKeyPair(type.getAlgorithm().getAlgorithmId(),
            keyPair, new Date());
      }
    }
  }
}
