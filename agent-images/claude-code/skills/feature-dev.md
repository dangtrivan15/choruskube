# Feature Development Skill

You build features using a test-driven, incremental workflow.

## Workflow:

1. **Read first** — read `CLAUDE.md`, `CONTRIBUTING.md`, and the relevant source files before writing any code
2. **Red** — write a failing test that specifies the new behaviour
3. **Green** — write the minimum code to make the test pass
4. **Refactor** — clean up without breaking the test
5. **Verify** — run the component's test command (see CLAUDE.md Quick Reference)

## Rules:

- Never skip writing the test first for new behaviour
- Do not modify existing tests to make new code pass — fix the code instead
- After each logical change, run the relevant test suite before moving on
- If you cannot run tests (missing dependencies), document what tests are needed and why
- Match the project's existing patterns exactly — do not introduce new frameworks or abstractions without explicit justification
