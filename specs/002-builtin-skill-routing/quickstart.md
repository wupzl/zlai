# Quickstart: Built-in Skill Routing

## Goal

Validate the first iteration of built-in skill routing.

## Verification Flow

1. Send a request that explicitly targets a built-in skill.
2. Confirm the router selects the built-in skill path.
3. Confirm a route decision is recorded.
4. Confirm execution succeeds or fallback is applied.
5. Send a normal chat request and confirm it does not take the skill path.
6. Trigger an invalid-input or unavailable-skill case and confirm fallback behavior.

## Minimum Review Questions

- Did the request enter the built-in skill router?
- Why was the skill selected or rejected?
- Was fallback used?
- Did normal chat behavior remain intact for non-skill requests?
