/*
 * GENERATED. Do not edit by hand.
 *
 * Source:      contracts/domain/limits.yaml
 * Generator:   tools/gen-limits/gen_limits.py
 * Regenerate:  ./sonder codegen
 */

// A hint for the interface, not a rule: the decision is the
// core's either way. Checking here is a courtesy to the user.
export const LIMITS = {
  nickMinLen: 3,
  nickMaxLen: 20,
  displayNameMaxLen: 60,
  postBodyMaxLen: 1000,
  commentBodyMaxLen: 500,
  banReasonMaxLen: 200,
  postsPerHour: 20,
  commentsPerHour: 60,
} as const

export type LimitName = keyof typeof LIMITS
