{ GENERATED. Do not edit by hand.

  Source:      contracts/domain/limits.yaml
  Generator:   tools/gen-limits/gen_limits.py
  Regenerate:  ./sonder codegen
}

{ Domain limits. Enforced by the core -- here they are not a
  hint but a rule: exceeding one is a refusal carrying a code
  from errors.yaml. }

const
  { Shortest nick }
  LIM_NICK_MIN_LEN             = 3;
  { Longest nick }
  LIM_NICK_MAX_LEN             = 20;
  { Longest display name }
  LIM_DISPLAY_NAME_MAX_LEN     = 60;
  { Longest post body }
  LIM_POST_BODY_MAX_LEN        = 1000;
  { Longest comment body }
  LIM_COMMENT_BODY_MAX_LEN     = 500;
  { Longest ban reason }
  LIM_BAN_REASON_MAX_LEN       = 200;
  { How many posts one user may create in an hour. Counted by the shell and sent to the core in the actor context: the core has neither a database nor a clock. }
  LIM_POSTS_PER_HOUR           = 20;
  { How many comments in an hour }
  LIM_COMMENTS_PER_HOUR        = 60;

