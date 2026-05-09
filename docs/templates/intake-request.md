# Intake Request

## Task ID

<!-- e.g., TASK-123, BUG-456, STORY-789 -->

## Task Type

<!-- Bug | Feature | Refactor | Investigation | Test Gap | Other -->

## Problem Statement

<!-- Describe what needs to be done or understood. One paragraph is enough. -->

## Actual Behavior

<!-- What is happening today. Include exact error messages, stack traces, or unexpected output. -->

## Expected Behavior

<!-- What should happen instead. -->

## Evidence

<!--
Paste or reference any supporting information:
- Logs or stack traces
- Screenshots (describe what they show)
- Failing test names and output
- Reproduction steps
- User reports or support tickets
- Related commits or PRs
-->

## Known Context

<!--
Background that Codex cannot derive from the repository alone:
- Architecture decisions
- Business rules or invariants
- Feature flags or rollout state
- Legacy behavior that must be preserved
- External service contracts
- Constraints on the solution
-->

## Claude Draft Analysis

<!--
What Claude currently believes may be happening, based on context gathered so far.
Include which files, classes, or modules seem involved and why.
Codex should verify or refute this analysis.

Leave blank if you want Codex to form its own analysis without anchoring bias.
-->

## What Codex Should Verify

<!--
Concrete questions for Codex to answer by inspecting the repository.
Be specific — vague questions produce vague answers.

Examples:
- Which modules or packages are likely involved in this flow?
- Which files implement the behavior described in the problem statement?
- Are there existing tests that cover this behavior? Are they passing?
- What tests are missing for this area?
- Are there configuration flags or feature flags that control this behavior?
- Does the code match or contradict the draft analysis above?
- What implementation risks exist if we change this area?
- Are there callsites outside the primary module that would need changes?
-->

## Scope Boundaries

<!--
Tell Codex which areas to focus on and which to avoid.

Allowed areas:
- (list of module paths, packages, or directories Codex should inspect)

Forbidden areas:
- (list of paths Codex should skip — legacy modules, unrelated services, generated code, etc.)
-->

## Output Format

<!-- yaml | json | markdown (default: yaml) -->
yaml
