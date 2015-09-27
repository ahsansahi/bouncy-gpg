package name.neuhalfen.projects.crypto.bouncycastle.examples.openpgp.encrypting;


import name.neuhalfen.projects.crypto.bouncycastle.examples.openpgp.shared.PGPUtilities;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.KeyFingerPrintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.SignatureException;
import java.util.Date;
import java.util.Iterator;

public class EncryptWithOpenPGP implements StreamEncryption {


    // make sure the Bouncy Castle provider is available:
    // because of this we can avoid declaring throws NoSuchProviderException further down
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * The Constant LOGGER.
     */
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(EncryptWithOpenPGP.class);


    /**
     * Milliseconds per second.
     */
    private static final int MLLIES_PER_SEC = 1000;

    /**
     * List of all secret key rings to be used.
     */
    private final PGPSecretKeyRingCollection secretKeyRings;


    /**
     * The signature secret key passphrase.
     */
    private final char[] signatureSecretKeyPassphrase;

    /**
     * The signature uid.
     */
    private final String signatureUid;

    /**
     * The encryption public key ring.
     */
    private final PGPPublicKeyRing encryptionPublicKeyRing;

    /**
     * code for the hash algorithm used for signing according to {@link org.bouncycastle.bcpg.HashAlgorithmTags}.
     */
    private final int hashAlgorithmCode;

    /**
     * code for the algorithm used for symmetric encryption according to
     * {@link org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags}.
     */
    private final int symmetricEncryptionAlgorithmCode;

    public EncryptWithOpenPGP(final EncryptionConfig config) throws IOException {

        try {
            final KeyFingerPrintCalculator keyFingerPrintCalculator = new BcKeyFingerprintCalculator();

            final PGPPublicKeyRingCollection publicKeyRings =
                    new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(
                            config.getPublicKeyRing()), keyFingerPrintCalculator);

            this.secretKeyRings =
                    new PGPSecretKeyRingCollection(PGPUtil.getDecoderStream(
                            config.getSecretKeyRing()), keyFingerPrintCalculator);
            this.signatureSecretKeyPassphrase = config.getSignatureSecretKeyPassphrase().toCharArray();

            this.signatureUid = config.getSignatureSecretKeyId();


            this.encryptionPublicKeyRing =
                    this.extractPublicKey(config.getEncryptionPublicKeyId(), publicKeyRings);

            this.hashAlgorithmCode = config.getPgpHashAlgorithmCode();
            this.symmetricEncryptionAlgorithmCode = config.getPgpSymmetricEncryptionAlgorithmCode();
        } catch (PGPException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Extracts the public key with UID {@code publicKeyUid} from key ring collection {@code publicKeyRings}.
     *
     * @param publicKeyUid   the public key uid
     * @param publicKeyRings the public key rings
     * @return the pGP public key ring
     * @throws PGPException the pGP exception
     */
    private PGPPublicKeyRing extractPublicKey(final String publicKeyUid,
                                              final PGPPublicKeyRingCollection publicKeyRings)
            throws PGPException {
        // the true parameter indicates, that partial matching of the publicKeyUid is enough.
        final Iterator<?> keyRings = publicKeyRings.getKeyRings(publicKeyUid, true);
        PGPPublicKeyRing returnKeyRing = null;
        while (keyRings.hasNext()) {
            final Object currentKeyRing = keyRings.next();
            if (currentKeyRing instanceof PGPPublicKeyRing) {
                if (returnKeyRing == null) {
                    returnKeyRing = (PGPPublicKeyRing) currentKeyRing;
                } else {
                    throw new PGPException("Multiple public key rings found for UID '" + publicKeyUid + "'!");
                }
            }
        }
        if (returnKeyRing == null) {
            throw new PGPException("No public key ring found for UID '" + publicKeyUid + "'!");
        }
        LOGGER.debug("Extracted public key ring for UID '{}' with first key strength {}.", publicKeyUid, returnKeyRing
                .getPublicKey().getBitStrength());
        return returnKeyRing;
    }

    @Override
    public void encryptAndSign(final InputStream is, final OutputStream os) throws IOException, RuntimeException,
            NoSuchAlgorithmException, SignatureException {
        final long starttime = System.currentTimeMillis();
        try {
            this.signThenEncrypt(is, os, this.getEncryptionKey(this.encryptionPublicKeyRing), true, true,
                    this.signatureSecretKeyPassphrase, this.hashAlgorithmCode, this.symmetricEncryptionAlgorithmCode);
        } catch (NoSuchProviderException anEx) {
            // This can't happen because we made sure of it in the static part at the top
            LOGGER.error("Bouncy Castle not available!?", anEx);
            throw new AssertionError("Bouncy Castle Provider is needed");
        } catch (PGPException e) {
            throw new RuntimeException(e);
        } finally {
            os.close();
        }
        LOGGER.debug("Encrypt and sign duration {}s", (System.currentTimeMillis() - starttime) / MLLIES_PER_SEC);
    }

    /**
     * Returns the first encryption key encountered in {@code publicKeyRing}.
     *
     * @param publicKeyRing the public key ring
     * @return the encryption key
     */
    private PGPPublicKey getEncryptionKey(final PGPPublicKeyRing publicKeyRing) {
        PGPPublicKey returnKey = null;
        final Iterator<?> kIt = publicKeyRing.getPublicKeys();
        while (returnKey == null && kIt.hasNext()) {
            final PGPPublicKey k = (PGPPublicKey) kIt.next();
            if (k.isEncryptionKey()) {
                returnKey = k;
            }
        }
        return returnKey;
    }


    /**
     * Method to sign-THEN-encrypt.
     *
     * @param in                               the in
     * @param out                              the out
     * @param pubEncKey                        the pub enc key
     * @param armor                            if OutputStream should be "armored", that means base64 encoded
     * @param withIntegrityCheck               the with integrity check
     * @param signingKeyPassphrase             the signing key passphrase
     * @param hashAlgorithmCode                code for the hash algorithm used for signing according to
     * @param symmetricEncryptionAlgorithmCode code for the algorithm used for symmetric encryption according to
     * @throws IOException              Signals that an I/O exception has occurred.
     * @throws NoSuchAlgorithmException the no such algorithm exception
     * @throws NoSuchProviderException  the no such provider exception
     * @throws PGPException             the pGP exception
     * @throws SignatureException       the signature exception
     *                                  {@link org.bouncycastle.bcpg.HashAlgorithmTags}
     *                                  {@link org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags}
     */
    protected void signThenEncrypt(final InputStream in, OutputStream out, final PGPPublicKey pubEncKey,
                                   final boolean armor, final boolean withIntegrityCheck, final char[] signingKeyPassphrase,
                                   final int hashAlgorithmCode, final int symmetricEncryptionAlgorithmCode) throws IOException,
            NoSuchAlgorithmException, NoSuchProviderException, PGPException, SignatureException {
        if (armor) {
            out = new ArmoredOutputStream(out);
        }

        final BcPGPDataEncryptorBuilder dataEncryptorBuilder = new BcPGPDataEncryptorBuilder(symmetricEncryptionAlgorithmCode);
        dataEncryptorBuilder.setWithIntegrityPacket(withIntegrityCheck);

        final PGPEncryptedDataGenerator cPk =
                new PGPEncryptedDataGenerator(dataEncryptorBuilder);


        cPk.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(pubEncKey));

        // this wraps the output stream in an encrypting output stream
        final OutputStream cOut = cPk.open(out, new byte[1 << 16]);

        final PGPSecretKey pgpSec = PGPUtilities.extractSecretSigningKeyFromKeyrings(this.secretKeyRings, this.signatureUid);


        final PGPPrivateKey pgpPrivKey = PGPUtilities.extractPrivateKey(pgpSec, signingKeyPassphrase);
        final PGPSignatureGenerator sGen =
                new PGPSignatureGenerator(new BcPGPContentSignerBuilder(pgpSec.getPublicKey().getAlgorithm(), hashAlgorithmCode));


        sGen.init(PGPSignature.BINARY_DOCUMENT, pgpPrivKey);

        final Iterator<?> it = pgpSec.getPublicKey().getUserIDs();
        if (it.hasNext()) {
            final PGPSignatureSubpacketGenerator spGen = new PGPSignatureSubpacketGenerator();

            spGen.setSignerUserID(false, (String) it.next());
            sGen.setHashedSubpackets(spGen.generate());
        }

        final PGPCompressedDataGenerator cGen = new PGPCompressedDataGenerator(PGPCompressedData.ZLIB);
        final BCPGOutputStream bOut = new BCPGOutputStream(cGen.open(cOut));
        sGen.generateOnePassVersion(false).encode(bOut);

        final PGPLiteralDataGenerator lGen = new PGPLiteralDataGenerator();
        final OutputStream lOut = lGen.open(bOut, PGPLiteralData.BINARY, "", new Date(), new byte[1 << 16]);
        // use of buffering to speed up write
        final byte[] buffer = new byte[1 << 16];

        int bytesRead = 0;
        while ((bytesRead = in.read(buffer)) != -1) {
            lOut.write(buffer, 0, bytesRead);
            sGen.update(buffer, 0, bytesRead);
            lOut.flush();
        }

        lGen.close();
        sGen.generate().encode(bOut);
        cGen.close();
        // ///end of sign

        // comData.close();
        cOut.close();
        out.close(); // as cOut does not forward close to out
    }

}