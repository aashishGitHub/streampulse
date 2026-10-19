# Phase 6B — Alert engine: Tasks

Each line is `test <Name> fails → implement <Class>`. Ticked only for what was run in this module.

Honest note on order: the domain classes and their tests were written in the same pass and the first
run was the first compile, so "fails first" was not observed for them. What was observed instead is
the mutation checks in ADR-0017's verification section: removing the BFS suppression turned 13 of 23
`SuppressionTest` cases red, and removing the idempotence guard turned 3 `AlertEngineTest` cases red.

- [x] T6B-01 spec, design, ADR-0017, `alerts-v1.yaml` written before adapters
- [x] T6B-02 `RuleGraphValidatorTest` (self-loop, 2-cycle, 3-cycle, order) → `RuleGraphValidator`, `Edge`, `CyclicDependencyException`
- [x] T6B-03 `DependencyGraphTest` (levels, diamond, 500-chain, rank) → `DependencyGraph`
- [x] T6B-04 `ThresholdEvaluatorTest`, `RateOfChangeEvaluatorTest`, `AbsenceEvaluatorTest` → strategies, `Evaluators`
- [x] T6B-05 `AlertStateMachineTest` → `AlertStateMachine`, `Alert`
- [x] T6B-06 `RuleBookTest` (versions, cycle, tenants) → `RuleBook`
- [x] T6B-07 `AlertEngineTest` (dedupe, resolve, refire, duplicate, stale, absence, retire) → `AlertEngine`
- [x] T6B-08 `SuppressionTest` (chain, long chain, diamond, release order, two roots, tenants) → BFS in `AlertEngine`
- [x] T6B-09 `AlertOutboxTest`, `AlertProcessorTest` → `AlertOutbox`, `AlertProcessor`
- [x] T6B-10 `AlertFeedTest` (ordering, replay, overflow, race) → `AlertFeed`
- [x] T6B-11 `AlertArchitectureRulesTest` → hexagonal layout
- [x] T6B-12 `AggregateDecoderTest`, `AggregateListenerTest`, `RuleJsonParserTest`, `ConfigChangeApplierTest`, `AlertEventMapperTest` → Kafka adapters
- [x] T6B-13 `AlertsApiContractTest`, `AlertApiTest`, `SseStreamTest` → controller, `SseStreamer`, problem handler
- [x] T6B-14 `AlertEngineWiringTest`, `MicrometerAlertMetricsTest` → composition root, metrics
- [x] T6B-15 `RuleBookLoaderIT`, `AlertEngineIT` (Kafka 3.9.1 + moto 5.2.2) → loader, Kafka and SQS channels
- [x] T6B-16 mutation checks (BFS, idempotence) with red then green output
- [x] T6B-17 verified output into `MILESTONES.md` (2026-10-19 close-out)
- [ ] T6B-18 restore open alerts from `alerts.v1` at start (not built; see ADR-0017)
