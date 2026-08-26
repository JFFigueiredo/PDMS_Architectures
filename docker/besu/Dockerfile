# 24.3.3, not the floating 24.3 tag: the deposited Besu captures report
# besu/v24.3.3/linux-x86 as the client that produced the decentralized results.
FROM hyperledger/besu:24.3.3
EXPOSE 8545 9545
CMD [ \
  "--data-path=/opt/besu/data", \
  "--network=dev", \
  "--rpc-http-enabled=true", \
  "--rpc-http-host=0.0.0.0", \
  "--rpc-http-port=8545", \
  "--rpc-http-api=ETH,NET,WEB3,MINER,ADMIN,TXPOOL", \
  "--host-allowlist=*", \
  "--miner-enabled=true", \
  "--miner-coinbase=0xfe3b557e8fb62b89f4916b721be55ceb828dbd73", \
  "--metrics-enabled=true", \
  "--metrics-host=0.0.0.0", \
  "--metrics-port=9545" \
]
