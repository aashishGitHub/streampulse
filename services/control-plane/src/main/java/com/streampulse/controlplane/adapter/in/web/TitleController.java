/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import com.streampulse.controlplane.adapter.in.web.dto.TitleDtos.CreateTitleRequest;
import com.streampulse.controlplane.adapter.in.web.dto.TitleDtos.TitleResponse;
import com.streampulse.controlplane.adapter.in.web.dto.TitleDtos.UpdateTitleRequest;
import com.streampulse.controlplane.application.TitleService;
import com.streampulse.controlplane.domain.Ids;
import com.streampulse.controlplane.domain.Title;
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
@RequestMapping("/v1/admin/titles")
public class TitleController {

    private final TitleService titles;

    public TitleController(TitleService titles) {
        this.titles = titles;
    }

    @GetMapping
    public List<TitleResponse> list(@RequestParam(required = false) String tenantId) {
        var tenant = tenantId == null ? null : Ids.tenantId(tenantId);
        return titles.list(tenant).stream().map(TitleResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<TitleResponse> create(@Valid @RequestBody CreateTitleRequest request) {
        Title stored = titles.create(
                new Title(request.titleId(), Ids.tenantId(request.tenantId()), request.name(), request.kind(), 0));
        return ResponseEntity.created(URI.create("/v1/admin/titles/" + stored.titleId()))
                .body(TitleResponse.from(stored));
    }

    @GetMapping("/{titleId}")
    public TitleResponse get(@PathVariable String titleId) {
        return TitleResponse.from(titles.get(titleId));
    }

    @PutMapping("/{titleId}")
    public TitleResponse update(@PathVariable String titleId, @Valid @RequestBody UpdateTitleRequest request) {
        return TitleResponse.from(titles.update(titleId, request.name(), request.kind()));
    }

    @DeleteMapping("/{titleId}")
    public ResponseEntity<Void> delete(@PathVariable String titleId) {
        titles.delete(titleId);
        return ResponseEntity.noContent().build();
    }
}
