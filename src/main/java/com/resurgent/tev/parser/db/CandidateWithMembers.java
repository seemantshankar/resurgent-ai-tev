package com.resurgent.tev.parser.db;

import java.util.List;

/** Candidate write payload plus member cell identities for a single replace batch. */
public record CandidateWithMembers(CandidateWrite write, List<Long> memberCellIds) {
}
