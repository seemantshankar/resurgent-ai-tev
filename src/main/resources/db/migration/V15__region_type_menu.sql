-- Shared enrichment region type menu, global to the workspace database.

CREATE TABLE region_type_menu (
    region_type_id INTEGER PRIMARY KEY AUTOINCREMENT,
    canonical_name TEXT NOT NULL UNIQUE
);

INSERT INTO region_type_menu (canonical_name) VALUES
    ('Project Cost'),
    ('Capital Cost'),
    ('Civil Cost'),
    ('Land'),
    ('Plant and Machinery'),
    ('P&L'),
    ('Balance Sheet'),
    ('Cash Flow'),
    ('Working Capital'),
    ('Depreciation'),
    ('Interest'),
    ('Sales'),
    ('Assumptions'),
    ('IRR'),
    ('Break-even'),
    ('CMA'),
    ('Tax'),
    ('Manpower'),
    ('Power');
