# AI_USAGE

**Tool:** Claude (Anthropic), via Claude Code, in a chat session.

**What the AI did:** The AI wrote the first full version of everything in this repo: the engine, the reference model, the invariant checker, all tests, and the four markdown files, based on the assignment text. It also proposed the resolutions of the rule conflicts in DECISIONS.md. It compiled the code and ran the tests, found one problem (the first stress test went vacuous because all seats sold out quickly) and fixed it, and it mutation-checked the suite by breaking the engine in a scratch copy.

**What I did:**

- Provided the assignment specification and requirements to Claude.
- Used the AI-generated implementation and documentation as the starting point for the repository.
- I have not yet independently implemented the solution or fully reviewed and understood all parts of the code.

**Not done by AI:** Nothing substantial independently so far.