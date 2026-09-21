## What does this change?

<!-- One or two sentences: what changed and why. -->

## Which requirement(s) does this address?

<!-- e.g. FR3, NFR "Idempotency" — see Chitthi — Requirements Document.md -->

## How was this tested?

- [ ] `mvn clean verify` passes locally
- [ ] Added/updated tests for the behavior changed
- [ ] Manually verified against local Docker Compose infra (if applicable)

## Checklist

- [ ] Commits are logically scoped (schema / feature / test / docs kept separate)
- [ ] No real Sarvam API calls added to the standard test suite (WireMock only)
- [ ] Idempotency/resumability guarantees are unaffected, or the change to them is intentional and tested
