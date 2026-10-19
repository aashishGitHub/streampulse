/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import com.streampulse.controlplane.adapter.in.web.dto.ServiceNodeDtos.CreateServiceNodeRequest;
import com.streampulse.controlplane.adapter.in.web.dto.ServiceNodeDtos.ServiceNodeResponse;
import com.streampulse.controlplane.adapter.in.web.dto.ServiceNodeDtos.UpdateServiceNodeRequest;
import com.streampulse.controlplane.application.ServiceNodeService;
import com.streampulse.controlplane.domain.Ids;
import com.streampulse.controlplane.domain.ServiceNode;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/service-nodes")
public class ServiceNodeController {

    private final ServiceNodeService nodes;

    public ServiceNodeController(ServiceNodeService nodes) {
        this.nodes = nodes;
    }

    @GetMapping
    public List<ServiceNodeResponse> list(@RequestParam(required = false) String tenantId) {
        var tenant = tenantId == null ? null : Ids.tenantId(tenantId);
        return nodes.list(tenant).stream().map(ServiceNodeResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ServiceNodeResponse> create(@Valid @RequestBody CreateServiceNodeRequest request) {
        ServiceNode stored = nodes.create(new ServiceNode(
                request.nodeId(),
                Ids.tenantId(request.tenantId()),
                request.name(),
                request.category(),
                request.dependsOn(),
                0));
        return ResponseEntity.created(URI.create("/v1/admin/service-nodes/" + stored.nodeId()))
                .body(ServiceNodeResponse.from(stored));
    }

    @GetMapping("/{nodeId}")
    public ServiceNodeResponse get(@PathVariable String nodeId) {
        return ServiceNodeResponse.from(nodes.get(nodeId));
    }

    @PutMapping("/{nodeId}")
    public ServiceNodeResponse update(
            @PathVariable String nodeId, @Valid @RequestBody UpdateServiceNodeRequest request) {
        return ServiceNodeResponse.from(nodes.update(nodeId, request.name(), request.category(), request.dependsOn()));
    }

    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Void> delete(@PathVariable String nodeId) {
        nodes.delete(nodeId);
        return ResponseEntity.noContent().build();
    }
}
