# Frozen baseline schemas

These are copies of the schemas **as currently released** — i.e. what is already written on
the Kafka topics and sitting in the S3 archive.

`SchemaCompatibilityTest` checks that the *current* schema in `src/main/avro` can still read
data written with **these**. If it cannot, the build fails.

## Updating a baseline

Copy the new schema over the baseline **in the same commit that changes it**:

```bash
cp libs/contracts/src/main/avro/TelemetryEvent.avsc \
   libs/contracts/src/test/resources/baseline/
```

This is deliberately a manual, visible act. A reviewer sees both halves of the change in one
diff and can ask "is this evolution actually safe?" — which is the entire point of the gate.

Updating the baseline to silence a failing test, without understanding why it failed, is how
you break every consumer at once.
