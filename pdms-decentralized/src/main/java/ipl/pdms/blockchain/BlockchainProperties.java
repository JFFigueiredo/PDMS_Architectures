package ipl.pdms.blockchain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "pdms.blockchain")
public class BlockchainProperties {

    private boolean enabled = false;
    /** e.g. http://besu:8545 */
    private String rpcUrl = "http://localhost:8545";
    /** Hex private key (0x…) for Besu dev funded account */
    private String privateKey;
    /** EIP-155 chain id — Besu --network=dev uses 1337 */
    private long chainId = 1337L;
    private int gasLimit = 8_000_000;
    private int scheduledFixedDelayMs = 500;
    private int deployMaxAttempts = 15;
}
