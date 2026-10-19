-- The follow graph. Flyway creates and owns the schema `activity_feed` (see application.yml):
-- the shared `public` schema already holds sink-writer's table, and Flyway refuses a non-empty
-- schema with no history, which is the same reason control-plane has its own.

CREATE TABLE follows (
    follower_id text        NOT NULL,
    followee_id text        NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id <> followee_id)
);

-- "Who follows X", scanned by keyset (follower_id > :after) and index-only. The primary key
-- already serves "whom does X follow", as a prefix scan on follower_id.
CREATE INDEX follows_by_followee ON follows (followee_id, follower_id);

-- Written in the same transaction as the follow row, and only when that row really changed.
CREATE TABLE follower_counts (
    user_id   text   PRIMARY KEY,
    followers bigint NOT NULL CHECK (followers >= 0)
);
