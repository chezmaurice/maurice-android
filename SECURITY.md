# Security policy

Maurice holds what a household says, writes and reads. A flaw that lets one
person reach another's data is the most serious kind of bug this project can
have, and reports are read with that in mind.

## Reporting a vulnerability

Please report privately, not in a public issue:

**[Report a vulnerability](https://github.com/chezmaurice/maurice-android/security/advisories/new)**
(GitHub private vulnerability reporting — only the maintainers see it).

Say what you found, where (file, route or screen), and how to reproduce it. A
proof of concept against your own installation is welcome; a guess is welcome
too, if you say it is one.

What happens next:

- an acknowledgement within 7 days;
- an assessment, and a fix or a reasoned answer, as fast as the severity
  calls for;
- once the fix has shipped, the advisory is published and you are credited,
  unless you prefer not to be.

## Scope

This repository: Maurice and Carnet for Android, Windows and Linux, and the
code they share (`core/`). The server they talk to lives in
[maurice-server](https://github.com/chezmaurice/maurice-server), under the same
policy; a flaw in a route belongs there.

Only the `main` branch and the latest released builds are supported; fixes
are not backported.

## Testing

Test against an installation you run yourself. Do not probe a hosted
household, or any household that is not yours: the data in it belongs to the
people who live there.

Research done in good faith within these lines will not be met with legal
action.

## What the system does not claim

Reports are most useful when measured against what Maurice promises. It does
not promise these, and says so to its members:

- no encryption against whoever runs the server: the operator of a household
  can read its storage. On a hosted household every look is written in a
  register its members can read — a rule of conduct, not a lock;
- sessions do not expire; they are revoked;
- an erasure does not reach off-site backups, archives already exported, or a
  git clone held elsewhere;
- an external MCP client connected with a member's token sees every tool
  mounted for that member.

A way to get *past* one of these lines — more than the stated limit allows —
is in scope.
