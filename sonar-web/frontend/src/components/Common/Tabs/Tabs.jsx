import './Tabs.css'

/** A row of tabs; the page decides which one is active. */
export default function Tabs({ tabs, active, onChange }) {
  return (
    <div className="Tabs" role="tablist">
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          aria-selected={tab.id === active}
          className={tab.id === active ? 'Tabs-tab active' : 'Tabs-tab'}
          onClick={() => onChange(tab.id)}
        >
          {tab.label}
          {tab.count !== undefined && <span className="Tabs-count">{tab.count}</span>}
        </button>
      ))}
    </div>
  )
}
