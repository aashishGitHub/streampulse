# Phase 10 — AWS simulation, Terraform and the archive compactor

**Status:** core complete, on **emulators only**. Eight Terraform modules validate; five were applied
against moto (13 resources, read back, destroyed). `msk`, `vpc` and `eks` are validate-only. The archive
compactor is tested but **never deployed**, and **nothing writes the raw archive it compacts**.

**One line to say first:** "Terraform that I actually applied, read back and destroyed against an AWS
emulator, with a written list of what that does and does not prove."

## Code map

| Thing | Where |
|---|---|
| Local environment (endpoint overrides) | `infra/terraform/envs/local/main.tf` |
| Modules | `infra/terraform/modules/kinesis`, `dynamodb`, `s3`, `sqs`, `iam` (applied); `msk`, `vpc`, `eks` (validate only) |
| What emulation proves and does not | `docs/adr/0003-local-aws-simulation.md`, `docs/adr/0019-local-only-terraform.md` |
| Compactor core: order of operations | `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/Compactor.java` |
| Which partitions to compact (quiet period) | `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPlanner.java` |
| The commit record | `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionManifest.java` |
| S3 adapter | `functions/archive-compactor/src/main/java/com/streampulse/compactor/adapter/out/S3ArchiveStore.java` |
| Compactor design | `docs/adr/0020-archive-compaction-design.md` |
| Region-loss thinking (design only) | `docs/multi-region-design.md` |

---

## Beginner

### B1. What do `terraform plan`, `apply` and `destroy` do, and what is state?

**Answer:** `plan` compares the configuration with the state Terraform has recorded and prints the changes it would make, without making them. `apply` carries those changes out, and `destroy` removes everything the state says Terraform created. State is the file that maps each block in the configuration to the real resource id, which is how Terraform knows that a bucket already exists and should not be created twice. In this repo the state is local and disposable, because it only describes an emulator, so there is no remote backend, no locking and no sharing between people. For a real account I would move it to a remote backend with locking, but that is not built here.

**Example:** `Makefile:231-235`
```make
tf-local-apply: ## apply envs/local against EMULATORS ONLY (proves API-level behaviour, never AWS)
	cd $(TF_DIR)/envs/local && terraform apply -input=false

tf-local-destroy: ## destroy what tf-local-apply created
	cd $(TF_DIR)/envs/local && terraform destroy -input=false
```
Point at the target names: the apply target says in its own help text that it is emulators only.

**Code:** `Makefile:228-235` (the plan, apply and destroy targets), `infra/terraform/envs/local/main.tf:1-3` (the local-only warning at the top of the environment)

### B2. What is moto and why use it?

**Answer:** Moto is an emulator of the AWS HTTP API. Terraform and the AWS SDK can be pointed at it, so I can create a stream, a bucket or a queue with no account, no cost and no real credentials. The catch is that it answers API calls but does not behave like the real services. It does not enforce IAM, it has no real throttling or quotas, and its latency means nothing. ADR-0003 chose it over LocalStack and a real account to keep the project free, and it says plainly that local success is not evidence that a policy or a design is correct on AWS.

**Example:** `docker/compose.yml:33-37`
```yaml
    image: motoserver/moto:5.2.2       # `latest` was a 5.2.3.dev build; 5.2.2 verified with `docker manifest inspect`
    profiles: [core]
    container_name: sp-moto
    ports:
      - "5001:5000"
```
The image tag is pinned, and the comment records why `latest` was rejected.

**Code:** `docker/compose.yml:33-37` (the moto service), `docs/adr/0003-local-aws-simulation.md` (the emulator choice and its stated limits)

### B3. What does the compactor do and why?

**Answer:** The compactor merges many small raw archive objects into one combined object per tenant, per day and per hour. Small objects are expensive to list and read, because every object costs at least one request and an open, and listing a prefix is paginated at 1,000 keys per call. ADR-0020 gives those as reasons, not measurements, because nothing has written a real archive to measure. The compactor also leaves a partition alone until its newest raw object is older than a quiet period, so it never merges something that is still being written. It also ignores a partition with fewer raw objects than a minimum, since merging one object into one gains nothing.

**Example:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPolicy.java:7-11`
```java
/**
 * @param quietPeriod how long a partition must have gone without a new object before it is touched
 * @param minInputObjects fewer raw objects than this are left alone: one object into one gains nothing
 */
public record CompactionPolicy(Duration quietPeriod, int minInputObjects) {
```
These two fields are the whole policy: when a partition is old enough, and how many objects make it worth merging.

**Code:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPolicy.java:7-11` (the policy), `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPlanner.java:65` (the quiet-period check)

### B4. What does least privilege mean for the IAM modules?

**Answer:** Least privilege means each service gets only the actions it needs, on only the resources it needs. The IAM module defines one policy per service, and the archive compactor's policy lists the bucket and grants get, put and delete only on keys under `tenant=`. I derived the verified policies by reading which SDK calls the code makes, and the three policies for the alerts consumer and the history writer and reader are marked unverified because no code calls those APIs yet. There is a wrinkle I state openly: the alert engine calls `CreateQueue` at start-up, so its policy has to include `sqs:CreateQueue`, which a pre-created queue would not need. Nothing here has been evaluated, because moto does not enforce IAM.

**Example:** `infra/terraform/modules/iam/main.tf:30-42`
```hcl
data "aws_iam_policy_document" "archive_compactor" {
  statement {
    sid       = "ListArchive"
    actions   = ["s3:ListBucket"]
    resources = [var.archive_bucket_arn]
  }

  statement {
    sid       = "ReadWriteDeleteArchiveObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${var.archive_bucket_arn}/tenant=*"]
  }
}
```
Two statements because listing acts on the bucket while object actions act on keys inside it; the object statement is narrowed to the `tenant=` prefix.

**Code:** `infra/terraform/modules/iam/main.tf:1-20` (what was verified from code and what was not), `infra/terraform/modules/iam/main.tf:44-50` (the alerts producer with `CreateQueue`)

**If they push:** "Is `DeleteObjects` covered by `s3:DeleteObject`?" The file says the mapping from SDK call to IAM action is from the AWS documentation as remembered and tells the reader to verify it against the current docs; I have not verified it.

### B5. How does `envs/local` use modules, and why wire ARNs through outputs?

**Answer:** Each module in `envs/local` owns one kind of resource, and the environment file passes the outputs of one module into the inputs of another. The IAM module receives the stream ARN from the Kinesis module, the bucket ARN from the S3 module, the queue ARN from the SQS module and the table ARN from the DynamoDB module. Terraform builds its dependency graph from those references, so it creates the stream before the policy that names it, and `destroy` tears them down in the reverse order. The benefit is that no ARN is typed by hand, so a policy cannot point at a resource that does not exist. The cost is that the environment is still emulator-only, and `msk`, `vpc` and `eks` are not wired into it at all.

**Example:** `infra/terraform/envs/local/main.tf:52-66`
```hcl
module "sqs" {
  source = "../../modules/sqs"

  tags = local.tags
}

module "iam" {
  source = "../../modules/iam"

  stream_arn         = module.kinesis.stream_arn
  archive_bucket_arn = module.s3.bucket_arn
  alerts_queue_arn   = module.sqs.queue_arn
  history_table_arn  = module.dynamodb.table_arn
  tags               = local.tags
}
```
The four `module.<name>.<output>` references are what create the ordering.

**Code:** `infra/terraform/envs/local/main.tf:32-66` (all five module calls), `infra/terraform/modules/iam/main.tf:116-123` (one `aws_iam_policy` per entry in the policy map)

---

## Mid

### M1. How do you point Terraform at an emulator?

**Answer:** In the AWS provider block I set test credentials, then turn off the checks that need a real account: credential validation, the account id lookup and the metadata API check. I switch S3 to path-style addressing, because an emulator has no wildcard DNS for a bucket name. Then an `endpoints` block overrides each service's URL to the moto address. The DynamoDB endpoint is `coalesce(var.dynamodb_endpoint, var.moto_endpoint)`, so it can be pointed at DynamoDB Local, but that does not work with this provider version, which is the finding in S2. The block does nothing to make the emulator faithful; it only redirects the calls, which is why everything it proves is API-level.

**Example:** `infra/terraform/envs/local/main.tf:5-23`
```hcl
provider "aws" {
  region     = var.region
  access_key = "test"
  secret_key = "test"

  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = "true"
  s3_use_path_style           = true

  endpoints {
    s3       = var.moto_endpoint
    kinesis  = var.moto_endpoint
    sqs      = var.moto_endpoint
    iam      = var.moto_endpoint
    sts      = var.moto_endpoint
    dynamodb = coalesce(var.dynamodb_endpoint, var.moto_endpoint)
  }
}
```
Point at the three skip flags and the `endpoints` block; the DynamoDB line is the only one with a fallback.

**Code:** `infra/terraform/envs/local/main.tf:5-23` (the provider), `infra/terraform/envs/local/variables.tf:12-21` (why DynamoDB Local is not usable)

**If they push:** "Why is `skip_metadata_api_check` a string?" I do not know; the file writes it as `"true"` and I have not checked the provider documentation for whether that is required, so I would verify it before copying the line.

### M2. In what order does the compactor work, and why that order?

**Answer:** For each partition it merges the inputs into a temporary file and counts the records while merging, then puts the combined object, then streams that object back and counts it again. Only if the two counts match does it write the manifest, and only after the manifest does it delete the inputs. Deleting before verifying would lose data if the write was bad, so the delete is last. The merge streams through a fixed 64 KB buffer into a temp file, so memory is bounded by construction, though I never measured it. It copies bytes rather than decoded text, so an invalid UTF-8 sequence is preserved. For the delete-first mutation, specs/10 names `corruptedWriteKeepsInputsAndRemovesTheBadTarget` as the test that went red, and ADR-0020 says six tests went red; I have not seen that run's output.

**Example:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/Compactor.java:125-146`
```java
            temp = Files.createTempFile(workDir, "compact-", ".ndjson.gz");
            long expected = mergeInto(temp, plan.inputs());

            String target = plan.targetKey();
            store.put(target, temp);

            long actual = countOf(target);
            if (actual != expected) {
                store.delete(List.of(target));
                ...
            }

            List<String> inputKeys =
                    plan.inputs().stream().map(ArchiveObject::key).toList();
            store.putText(plan.manifestKey(), new CompactionManifest(expected, inputKeys).render());
            store.delete(inputKeys);
```
Read it top to bottom: merge, put, re-read and compare, manifest, delete. On a mismatch the bad target is removed and the inputs are never touched.

**Code:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/Compactor.java:122-153` (the method), `functions/archive-compactor/src/test/java/com/streampulse/compactor/domain/CompactorTest.java:116-131` (the corrupted-write test)

**If they push:** "How do you know the test can fail?" Task T10-07 in `specs/10-aws-terraform/tasks.md` records a mutation that deletes the inputs before verifying and says the corrupted-write test went red before the code was restored.

### M3. What is the manifest for?

**Answer:** The manifest is the commit record. It is written only after the combined object has been verified, so its existence means that these source objects are safely folded in. On the next run the compactor reads the manifests of partitions that still hold raw objects, and a raw object named in a manifest is a leftover to delete, never data to merge again. That is what makes a crash part-way through the deletes safe, because the surviving inputs are cleaned up instead of being merged into a second combined object, which would duplicate their records. The format is deliberately tiny, a `records=N` header line followed by one source key per line. An unreadable manifest fails only its own partition, with a test for that.

**Example:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionManifest.java:10-24`
```java
/**
 * The commit record of one compaction: written only after the combined object has been verified,
 * so its existence means "these sources are safely folded in".
 */
public record CompactionManifest(long records, List<String> sources) {

    private static final Pattern HEADER = Pattern.compile("records=(\\d+)");

    public CompactionManifest {
        sources = List.copyOf(sources);
    }

    public String render() {
        return "records=" + records + "\n" + String.join("\n", sources) + "\n";
    }
```
The Javadoc is the contract: the manifest is written after verification, never before.

**Code:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionManifest.java:10-35` (render and parse), `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/Compactor.java:71-101` (reading manifests), `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPlanner.java:69-77` (leftovers versus fresh inputs)

**If they push:** "What if the manifest cannot be parsed?" That partition is reported as failed with an "unreadable manifest" message and is excluded from planning, and the other partitions continue.

### M4. How do you test time-dependent code like the quiet period?

**Answer:** The code never reads the system clock directly. The planner is a pure function that takes the current instant as an argument, and the compactor takes one reading from an injected `Clock` per run, so every partition is judged at the same moment. The unit tests use a fixed clock. The integration test needs a movable clock, because moto stamps `LastModified` with real time, so it sets the clock one hour ahead of everything written so far. The planner's check compares the newest raw object's modification time plus the quiet period against now, and it looks only at raw objects, because the combined object the compactor writes has a fresh timestamp that must not make the partition look busy. For the mutation, tasks.md records that removing the check turned `partitionStillBeingWrittenIsNeverTouched` red.

**Example:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPlanner.java:59-67`
```java
        // Only raw objects count: a combined object is written by us, and its fresh timestamp must
        // not make the partition look busy.
        Instant newest = raw.stream()
                .map(ArchiveObject::lastModified)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        if (newest.plus(policy.quietPeriod()).isAfter(now)) {
            return new PartitionPlan(partition, List.of(), List.of(), SkipReason.STILL_BEING_WRITTEN);
        }
```
`now` comes in as a parameter, so a test decides what time it is.

**Code:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/CompactionPlanner.java:28` (`plan` takes `now`), `functions/archive-compactor/src/test/java/com/streampulse/compactor/ArchiveCompactionIT.java:66-72` (the movable clock), `functions/archive-compactor/src/test/java/com/streampulse/compactor/domain/CompactorTest.java:133-145` (the still-being-written test)

**If they push:** "Is a partition exactly one quiet period old compacted?" Yes, `quietPeriodBoundaryIsInclusive` in `CompactionPlannerTest` pins that, and one millisecond newer is held back.

### M5. Why does the S3 adapter check the response of `DeleteObjects`?

**Answer:** `DeleteObjects` answers 200 even when individual keys fail, so a delete can partially fail while the call looks successful. The adapter therefore reads the response, and if there are any errors it throws, naming the first failed key and its error code. That matters for the compactor's order of operations: the manifest has already been written by then, so raising keeps the manifest in place and the next run finishes the deletion instead of merging again. The adapter also batches deletes at 1,000 keys, which is S3's hard limit for one request. I will be honest about the evidence: the emulator never produces per-key errors, so this path is covered only by a unit test that mocks the client, not by a real S3 run.

**Example:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/adapter/out/S3ArchiveStore.java:80-89`
```java
            DeleteObjectsResponse response = s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(batch).quiet(true).build())
                    .build());
            // DeleteObjects answers 200 even when individual keys fail, exactly like Kinesis PutRecords.
            if (response.hasErrors() && !response.errors().isEmpty()) {
                var first = response.errors().get(0);
                throw new IllegalStateException("S3 could not delete %d of %d keys; first: %s (%s)"
                        .formatted(response.errors().size(), batch.size(), first.key(), first.code()));
            }
```
The comment is the reason the check exists; without it a partial failure would pass silently.

**Code:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/adapter/out/S3ArchiveStore.java:73-91` (batched delete), `functions/archive-compactor/src/test/java/com/streampulse/compactor/adapter/S3ArchiveStoreTest.java` (the mocked-client unit test)

### M6. Why is the combined object's name a hash of its inputs?

**Answer:** A rerun after a crash must not leave a second copy of the data. The combined key embeds the first 16 hex characters of a SHA-256 over the sorted input keys, so the same set of inputs always produces the same key. If the compactor crashes after writing the combined object but before the manifest, the next run sees the same inputs, computes the same key and overwrites identical bytes. The manifest key uses the same fingerprint. A test shows that the key depends only on the set of inputs, not their order, and changes when an input is added or removed. The limit is that a late arrival changes the input set, so it produces a second combined object instead of joining the first, which ADR-0020 lists as a known limit and `lateArrivalsBecomeASecondCombinedObject` pins.

**Example:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/PartitionPlan.java:39-52`
```java
    // WHY the key is a hash of the input set: a re-run after a crash sees the same inputs, so it
    // computes the same key and overwrites identical bytes instead of leaving a second copy.
    private String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (ArchiveObject input : inputs) {
                digest.update(input.key().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
```
The planner sorts inputs by key before they reach this loop, which is what makes the hash stable.

**Code:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/domain/PartitionPlan.java:26-37` (target and manifest keys), `functions/archive-compactor/src/test/java/com/streampulse/compactor/domain/CompactionPlannerTest.java:144-161` (`targetKeyIsDeterministic`)

---

## Senior

### S1. What does a successful `apply` against moto not prove?

**Answer:** It proves that the configuration is well formed, that the provider's calls were answered, that names and shapes can be read back, and that dependency order and `destroy` cleanup work. It does not prove that real AWS accepts the same configuration, because emulators are more permissive. It proves nothing about IAM, since a policy that would deny in production succeeds here. Nothing about network isolation, quotas, throttling, latency or cost is tested, and an emulator can accept an attribute and silently ignore it. That is why `msk`, `vpc` and `eks` are validate-only: a green apply of a VPC says nothing about isolation, and real MSK and EKS clusters cost money. `validate` still catches an unknown argument or a bad reference in those modules, but not a value the real service rejects. This is the table in ADR-0019, and I have never run any of it against a real account.

**Example:** `infra/terraform/envs/validate/main.tf:5-12`
```hcl
provider "aws" {
  region                      = "us-east-1"
  access_key                  = "validate-only"
  secret_key                  = "validate-only"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = "true"
}
```
Compare it with the local provider: there is no `endpoints` block, so this root has nothing to apply against, which is why the file's header says it must never be planned or applied.

**Code:** `docs/adr/0019-local-only-terraform.md` (the proves and does-not-prove table, and the validate-only reasons), `infra/terraform/envs/local/main.tf:1-3` (the in-file warning), `infra/terraform/envs/validate/main.tf:1-3` (the validate-only warning)

**If they push:** "So what would you do before trusting the IAM policies?" Apply against a throwaway real account and exercise each service's calls, because the emulator cannot deny anything; I have not done that.

### S2. The second plan shows three changes with no configuration change. Is that drift?

**Answer:** It is emulator round-trip loss, not drift. After the apply, a second `plan` showed 3 resources changing in place. ADR-0019 says the Kinesis stream and the S3 bucket show their tags as missing because moto accepts tags and does not return them, and the main alert queue shows a whitespace change in `redrive_policy` because moto re-serialises the JSON it stores. The MILESTONES entry words it as tags on the queue, so I would re-run the plan to be sure which resources it names. I diagnosed it by reading the resources back with the AWS CLI. The redrive policy came back as the expected dead-letter ARN and `maxReceiveCount` of 5. Against real AWS I expect a clean second plan, but I have not seen that. In production I would use a remote backend with locking, and I would use `ignore_changes` only with a reason.

**Example:** `infra/terraform/modules/sqs/main.tf:7-18`
```hcl
resource "aws_sqs_queue" "alerts" {
  name                       = var.name
  visibility_timeout_seconds = var.visibility_timeout_seconds
  message_retention_seconds  = var.message_retention_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.alerts_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })

  tags = var.tags
}
```
The policy is built with `jsonencode`, so the configuration side is already normalised; the whitespace difference comes from the emulator.

**Code:** `infra/terraform/modules/sqs/main.tf:7-18` (the queue and its redrive policy), `MILESTONES.md:1467-1470` (the second plan, "Honest gaps" follows), `docs/adr/0019-local-only-terraform.md` ("What did not work")

**If they push:** "What else went wrong with the emulators?" DynamoDB Local made `apply` hang with provider 6.68.0: the table reported ACTIVE but the provider kept polling `DescribeTable`, and the cause is unproven. So the table was applied against moto, a lower-fidelity DynamoDB, and ADR-0003's table still lists DynamoDB Local, which contradicts that.

### S3. Walk through the compactor's crash points.

**Answer:** Four points matter. A crash before the combined object is put leaves nothing, and the next run starts over. A crash after the put but before the manifest leaves an unreferenced combined object, and the rerun computes the same key and overwrites it with identical bytes. A crash after the manifest and before any delete leaves the combined object, the manifest and all the inputs, and the next run treats every input as a leftover and deletes it. A crash part-way through the deletes is the same case with fewer survivors, and it is the reason for the manifest. Tests cover these, including the delete crash recovering without duplicates. The limits are real: there is no conditional delete, so raw keys are assumed write-once; a late arrival creates a second combined object; and overlapping runs are safe by reasoning, not by a test.

**Example:** `functions/archive-compactor/src/test/java/com/streampulse/compactor/domain/CompactorTest.java:196-210`
```java
    @Test
    @DisplayName("a crash part-way through deleting is finished from the manifest, not by merging the survivors again")
    void crashDuringDeleteIsRecoveredWithoutDuplicates() {
        threeObjectsIn(H12);
        store.deleteFailsAfter(2);

        compactor.compact(Scope.all());
        assertThat(rawKeys()).hasSize(1);
        CompactionReport second = compactor.compact(Scope.all());

        assertThat(outcomeFor(second, H12).status()).isEqualTo(Status.CLEANED_UP);
        assertThat(rawKeys()).isEmpty();
        assertThat(store.keysMatching(COMBINED)).hasSize(1);
        assertThat(store.recordsIn(store.keysMatching(COMBINED).get(0))).isEqualTo(6);
    }
```
The first run leaves one survivor; the second cleans up, and the single combined object holds exactly 6 records.

**Code:** `docs/adr/0020-archive-compaction-design.md` (the crash table and the known limits), `functions/archive-compactor/src/test/java/com/streampulse/compactor/domain/CompactorTest.java:178-194` (crash before the manifest), `functions/archive-compactor/src/test/java/com/streampulse/compactor/ArchiveCompactionIT.java:238` (`interruptedDeleteIsFinishedByTheNextRun` against the emulator)

**If they push:** "What about two runs at once?" Same inputs give the same key and the same bytes, and deletes are idempotent, so I reason that it is safe; there is no test for it, and I say so.

### S4. How would you deploy this, and what would change?

**Answer:** It would need packaging and a trigger first. It is a Spring Cloud Function, and the build file says it has never been deployed as a real serverless function. The Lambda adapter is not added, and ADR-0020 says a deployment needs a shaded jar rather than the Boot fat jar, neither of which is built or tested. Then a schedule as the trigger, since the function already accepts an optional tenant and date to scope a run. I would check memory and timeout against object sizes: memory is bounded by streaming but unmeasured, and the temp file equals the compressed hour, which a bounded scratch directory could exceed. ADR-0020 recalls Lambda limits of 512 MB to 10 GB scratch and 15 minutes, which I have not verified. I would also check the IAM policy against the real calls. And nothing writes raw archive objects yet, so a deployed compactor would have nothing to do.

**Example:** `functions/archive-compactor/build.gradle.kts:1-8`
```kotlin
// ============================================================================
//  archive-compactor — a Lambda-shaped function that compacts the raw archive.
//
//  A Spring Cloud Function `java.util.function.Function` registered in the function
//  catalog. It runs in-process and in tests; it has NEVER been deployed as a real
//  serverless function (ADR-0020). The Lambda adapter would be a deploy-time
//  concern and is not added here.
// ============================================================================
```
This is the build file's own statement of what is and is not done.

**Code:** `functions/archive-compactor/src/main/java/com/streampulse/compactor/adapter/in/CompactArchiveFunction.java:22-29` (the entry point and its optional tenant and date), `functions/archive-compactor/src/main/java/com/streampulse/compactor/config/CompactorConfig.java:17-22` (the compactor bean and its scratch directory)

**If they push:** "Why not fix it at the writer instead?" ADR-0020 calls that the better long-term answer once a writer exists, and compaction would still be needed for the tail of small objects and for late data. The writer does not exist here.

### S5. Region loss: what does the design claim and what is it worth?

**Answer:** `docs/multi-region-design.md` sets a recovery point of at most 60 seconds of raw telemetry and a recovery time of at most 10 minutes. Both are budgets to measure against, not promises. The document says it assumes tens of seconds of replication lag in steady state, and that cross-region lag under load is unmeasured. The recovery time is a sum of assumed parts: health-check detection, DNS TTL, promoting the Postgres replica and replaying. The document itself says that if the lag cannot hold 60 seconds, the number or the architecture should change rather than the sentence staying in place. The whole document is design only; nothing in it was built, simulated or run, and its service capabilities are marked `VERIFY`. In an interview I use it to show how I reason about RPO and RTO, not as a claim about the running system.

**Example:** `docs/multi-region-design.md:86-86`
```markdown
| **RPO** (data we may lose) | **<= 60 seconds of raw telemetry** | Asynchronous mirroring lags by however long the link and the replicator take. I assume tens of seconds in steady state and set 60 s as a budget to measure against. Cross-region lag under load is unmeasured and has to be measured (Phase 9 style) before this is a promise |
```
The reasoning column is the point: the number is stated together with why it is only an assumption.

**Code:** `docs/multi-region-design.md:80-91` (the targets table), `docs/multi-region-design.md:1-8` (the design-only status)

**If they push:** "How would you make it real?" The document's own next step is to measure cross-region replication lag under load and adjust the target or the design, then verify each `VERIFY` against current provider documentation.

---

## Say this honestly

- Emulators only; `terraform fmt` and `validate` are not in CI. GitHub Actions runs `./gradlew spotlessCheck` and `./gradlew build` only; `make tf-validate` is a local target.
- Point-in-time recovery is untested; no service writes the archive bucket or the aggregate-history table.
- The compactor runs only in-process and in tests. Its memory bound is by construction, not measured.

## Stories and numbers

[Numbers](../06-numbers.md): Terraform and compactor test rows.
