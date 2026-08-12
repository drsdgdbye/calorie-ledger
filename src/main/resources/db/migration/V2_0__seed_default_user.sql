INSERT INTO users (id, username)
VALUES (1, 'default')
ON CONFLICT (id) DO NOTHING;
