# Compose changes for docker/compose.yml

Apply to the existing `prometheus` and `grafana` services (profile `obs`). Paths are relative
to `docker/compose.yml`.

```yaml
  prometheus:
    # ...existing image, profiles, container_name, ports ("9091:9090")...
    volumes:
      - ./observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    extra_hosts:
      - "host.docker.internal:host-gateway"

  grafana:
    # ...existing image, profiles, container_name, ports, environment...
    volumes:
      - ./observability/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./observability/grafana/dashboards:/var/lib/grafana/dashboards:ro
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

Notes:

- The `prometheus` volume line already exists in compose.yml; it is repeated so the block is
  complete. Only `extra_hosts` is new there.
- `extra_hosts` is what makes `host.docker.internal` resolve on Linux. Docker Desktop (macOS,
  Windows) defines the name already; the entry is harmless there.
- Prometheus is published on host port 9091 (container port 9090), because ingest-gateway's
  gRPC server takes host 9090. Open http://localhost:9091. Grafana reaches it over the compose
  network as `http://prometheus:9090` (the container port), which is what the provisioned
  datasource uses.
- Grafana is at http://localhost:3000; the dashboard "StreamPulse Pipeline" appears in the
  "StreamPulse" folder. Mounting `/etc/grafana/provisioning` replaces the image's default
  (empty) provisioning tree, so keep every provisioning file under
  `observability/grafana/provisioning/`.
- Start with: `docker compose --profile obs up -d prometheus grafana` (from `docker/`).
  I have not run this.
