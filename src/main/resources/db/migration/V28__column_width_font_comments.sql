-- Column width, font, and cell comments. Drawings stay out of the ingest contract.

ALTER TABLE cell_style ADD COLUMN font_name TEXT;
ALTER TABLE cell_style ADD COLUMN font_size INTEGER;
ALTER TABLE cell_style ADD COLUMN italic INTEGER CHECK (italic IN (0, 1));
ALTER TABLE cell_style ADD COLUMN underline TEXT;
ALTER TABLE cell_style ADD COLUMN font_color TEXT;

DROP INDEX idx_cell_style_identity;
CREATE UNIQUE INDEX idx_cell_style_identity ON cell_style (
    COALESCE(is_bold, -1),
    COALESCE(number_format, ''),
    COALESCE(fill_fg_color, ''),
    COALESCE(fill_pattern, ''),
    COALESCE(border_top_style, ''),
    COALESCE(border_top_color, ''),
    COALESCE(border_right_style, ''),
    COALESCE(border_right_color, ''),
    COALESCE(border_bottom_style, ''),
    COALESCE(border_bottom_color, ''),
    COALESCE(border_left_style, ''),
    COALESCE(border_left_color, ''),
    COALESCE(font_name, ''),
    COALESCE(font_size, -1),
    COALESCE(italic, -1),
    COALESCE(underline, ''),
    COALESCE(font_color, '')
);

CREATE TABLE worksheet_column (
    worksheet_id INTEGER NOT NULL REFERENCES worksheet (worksheet_id),
    col_num INTEGER NOT NULL,
    width INTEGER NOT NULL,
    PRIMARY KEY (worksheet_id, col_num)
);

CREATE TABLE cell_comment (
    cell_id INTEGER PRIMARY KEY REFERENCES cell (cell_id),
    author TEXT,
    body TEXT NOT NULL
);
