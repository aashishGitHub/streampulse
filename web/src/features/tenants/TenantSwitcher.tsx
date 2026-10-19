import type { TenantList } from './useTenants';

interface Props {
  tenants: TenantList;
  value: string;
  onChange: (tenantId: string) => void;
}

export function TenantSwitcher({ tenants, value, onChange }: Props) {
  return (
    <div className="tenant-switcher">
      <label>
        Tenant{' '}
        <select value={value} onChange={(event) => onChange(event.target.value)}>
          {tenants.options.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      {tenants.status === 'fallback' && (
        <p className="note" role="status">
          Tenant list unavailable; showing the configured tenants.
        </p>
      )}
    </div>
  );
}
