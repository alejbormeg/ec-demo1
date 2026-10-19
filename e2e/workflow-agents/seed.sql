CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE TABLE IF NOT EXISTS synthetic_workflow_handbook (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  content text,
  metadata json,
  embedding vector(3)
);
INSERT INTO synthetic_workflow_handbook(content, metadata, embedding)
VALUES ('Synthetic rule: accept a case only when its evidence is present and verified.',
        '{"source":"synthetic-handbook","synthetic":true}', '[1,0,0]');
