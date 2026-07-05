package com.choruskube.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("orgSecurity")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpOrgSecurity implements OrgSecurity {

    @Override
    public boolean canRead() {
        return true;
    }

    @Override
    public boolean canOperate() {
        return true;
    }

    @Override
    public boolean canAdmin() {
        return true;
    }

    @Override
    public boolean isPlatformAdmin() {
        return true;
    }
}
