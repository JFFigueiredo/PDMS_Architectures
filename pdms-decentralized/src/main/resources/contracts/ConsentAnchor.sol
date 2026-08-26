// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.19;

/**
 * Minimal consent integrity anchor for permissioned Besu networks.
 * Stores no PII on-chain. consentId and personId are per-subject salted HMACs, not the
 * raw UUIDs: the salt lives off-chain in person_anchor_salt and is destroyed on erasure,
 * so anchors for an erased subject can no longer be recomputed or linked to each other.
 * payloadHash is SHA-256 over the canonical consent JSON (same as the PG hash chain).
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
