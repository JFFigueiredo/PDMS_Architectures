package ipl.pdms.blockchain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * Deploys {@code ConsentAnchor} on Besu dev (or any EVM JSON-RPC) and sends anchor transactions.
 */
@Service
@ConditionalOnProperty(name = "pdms.blockchain.enabled", havingValue = "true")
@Slf4j
public class BesuConsentAnchorClient {

    private final Web3j web3j;
    private final BlockchainProperties properties;
    private final BlockchainStateRepository stateRepository;
    private final Credentials credentials;

    private volatile String contractAddressCache;
    /** Resolved once from {@code eth_chainId} so signatures match the connected Besu network. */
    private volatile Long chainIdForSigning;

    public BesuConsentAnchorClient(
            Web3j web3j,
            BlockchainProperties properties,
            BlockchainStateRepository stateRepository
    ) {
        this.web3j = web3j;
        this.properties = properties;
        this.stateRepository = stateRepository;
        String pk = properties.getPrivateKey();
        if (pk == null || pk.isBlank()) {
            throw new IllegalStateException("pdms.blockchain.private-key is required when blockchain is enabled");
        }
        this.credentials = Credentials.create(pk.startsWith("0x") ? pk : ("0x" + pk));
    }

    public synchronized void ensureContractDeployed() throws Exception {
        if (contractAddressCache != null && !contractAddressCache.isBlank()) {
            return;
        }
        Optional<String> fromDb = stateRepository.findContractAddress();
        if (fromDb.isPresent()) {
            contractAddressCache = fromDb.get();
            log.info("Using ConsentAnchor contract at {}", contractAddressCache);
            return;
        }
        String bytecodeHex = loadBytecodeHex();
        BigInteger nonce = nextNonce();
        RawTransaction raw = RawTransaction.createContractTransaction(
                nonce,
                BigInteger.ZERO,
                BigInteger.valueOf(properties.getGasLimit()),
                BigInteger.ZERO,
                bytecodeHex
        );
        String txHash = signAndSend(raw);
        TransactionReceipt receipt = waitForReceipt(txHash);
        String deployed = receipt.getContractAddress();
        if (deployed == null || deployed.isBlank()) {
            throw new IllegalStateException("Contract deployment receipt missing contractAddress");
        }
        stateRepository.updateContractAddress(deployed);
        contractAddressCache = deployed;
        log.info("Deployed ConsentAnchor at {} (tx {})", deployed, txHash);
    }

    public TransactionReceipt sendAnchor(
            UUID consentId,
            UUID personId,
            String payloadHashHex64,
            int action
    ) throws Exception {
        ensureContractDeployed();
        String contract = contractAddressCache;
        Function function = new Function(
                "anchor",
                Arrays.asList(
                        new Bytes32(uuidToBytes32(consentId)),
                        new Bytes32(uuidToBytes32(personId)),
                        new Bytes32(Numeric.hexStringToByteArray(toHex0x(payloadHashHex64))),
                        new Uint8(BigInteger.valueOf(action))
                ),
                Collections.emptyList()
        );
        String encoded = FunctionEncoder.encode(function);
        BigInteger nonce = nextNonce();
        RawTransaction raw = RawTransaction.createTransaction(
                nonce,
                BigInteger.ZERO,
                BigInteger.valueOf(properties.getGasLimit()),
                contract,
                BigInteger.ZERO,
                encoded
        );
        String txHash = signAndSend(raw);
        return waitForReceipt(txHash);
    }

    private long resolveChainIdForSigning() throws Exception {
        Long c = chainIdForSigning;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (chainIdForSigning != null) {
                return chainIdForSigning;
            }
            BigInteger id = web3j.ethChainId().send().getChainId();
            chainIdForSigning = id.longValue();
            log.info("Besu eth_chainId for signing: {} (pdms.blockchain.chain-id was {})", chainIdForSigning, properties.getChainId());
            return chainIdForSigning;
        }
    }

    private String signAndSend(RawTransaction raw) throws Exception {
        byte[] signed = TransactionEncoder.signMessage(raw, resolveChainIdForSigning(), credentials);
        String hexValue = Numeric.toHexString(signed);
        EthSendTransaction response = web3j.ethSendRawTransaction(hexValue).send();
        if (response.hasError()) {
            throw new IllegalStateException("eth_sendRawTransaction: " + response.getError().getMessage());
        }
        return response.getTransactionHash();
    }

    private BigInteger nextNonce() throws Exception {
        EthGetTransactionCount cnt = web3j.ethGetTransactionCount(
                credentials.getAddress(),
                DefaultBlockParameterName.LATEST
        ).send();
        return cnt.getTransactionCount();
    }

    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        int max = 120;
        for (int i = 0; i < max; i++) {
            var opt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (opt.isPresent()) {
                return opt.get();
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Timeout waiting for tx receipt: " + txHash);
    }

    private String loadBytecodeHex() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/contracts/out/ConsentAnchor.bin")) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource /contracts/out/ConsentAnchor.bin not found");
            }
            String hex = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return toHex0x(hex);
        }
    }

    private static String toHex0x(String hexNoPrefixOrWith) {
        String h = Numeric.cleanHexPrefix(hexNoPrefixOrWith);
        return "0x" + h;
    }

    /**
     * 32-byte on-chain identifier for a UUID: SHA-256 of its 16 raw bytes.
     *
     * <p>This previously zero-padded the UUID itself, which put a directly readable
     * identifier on an append-only ledger where erasure cannot reach it. A UUIDv4 carries
     * about 122 bits of entropy, so the digest is not open to a dictionary or brute-force
     * attack the way a hashed email address would be, and destroying the row that holds
     * the UUID leaves nothing from which the digest can be recomputed.
     *
     * <p>The digest is still a stable pseudonym: the same UUID always maps to the same
     * value, so anchors for one subject remain linkable to each other for as long as the
     * UUID survives anywhere off-chain. Breaking that would need a per-subject salt that
     * erasure destroys; it is not done here.
     */
    private static byte[] uuidToBytes32(UUID uuid) {
        byte[] raw = ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
