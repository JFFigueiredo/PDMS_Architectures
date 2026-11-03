// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.19;

/**
 * Minimal consent integrity anchor for permissioned Besu networks.
 * The Java client supplies consentId and personId as deterministic SHA-256 digests
 * of UUID bytes. No per-subject salt or HMAC is implemented. These stable pseudonyms
 * keep anchors carrying the same digest linkable after off-chain erasure.
 * payloadHash is SHA-256 over the canonical consent payload used by the PG hash chain.
 */
contract ConsentAnchor {
    event Anchored(
        bytes32 indexed consentId,
        bytes32 indexed personId,
        bytes32 payloadHash,
        uint8 action
    );

    function anchor(
        bytes32 consentId,
        bytes32 personId,
        bytes32 payloadHash,
        uint8 action
    ) external {
        emit Anchored(consentId, personId, payloadHash, action);
    }
}
