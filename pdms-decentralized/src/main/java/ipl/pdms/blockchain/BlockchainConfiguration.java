package ipl.pdms.blockchain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

@Configuration
@EnableConfigurationProperties(BlockchainProperties.class)
@ConditionalOnProperty(name = "pdms.blockchain.enabled", havingValue = "true")
public class BlockchainConfiguration {

    @Bean
    public Web3j web3j(BlockchainProperties properties) {
        return Web3j.build(new HttpService(properties.getRpcUrl(), false));
    }
}
