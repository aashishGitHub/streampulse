/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import com.streampulse.controlplane.domain.TenantTreeNode;
import com.streampulse.controlplane.domain.TenantTreeReader;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantTreeService {

    private final TenantTreeReader reader;

    public TenantTreeService(TenantTreeReader reader) {
        this.reader = reader;
    }

    /** One transaction, so every statement of the reader shares one persistence context. */
    @Transactional(readOnly = true)
    public List<TenantTreeNode> tree() {
        return reader.readTree();
    }
}
