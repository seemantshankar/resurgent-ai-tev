-- Ticket 51: persist structured worksheet-role reasons alongside role and role_conf.
ALTER TABLE worksheet ADD COLUMN role_reasons TEXT;
