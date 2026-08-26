package ipl.pdms.blockchain;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pdms.blockchain.enabled", havingValue = "true")
public class BlockchainStateRepository {

    private final JdbcTemplate jdbc;

    public Optional<String> findContractAddress() {
        return Optional.ofNullable(
                jdbc.query(
                        "SELECT contract_address FROM blockchain_integration_state WHERE singleton = 1",
                        rs -> rs.next() ? rs.getString(1) : null
                )
        ).filter(s -> s != null && !s.isBlank());
    }

    public void updateContractAddress(String address) {
        jdbc.update(
                "UPDATE blockchain_integration_state SET contract_address = ? WHERE singleton = 1",
                address
        );
    }
}
