-- Self-learning train number -> name catalog.
--
-- Some aggregators (e.g. RailRecipe) send only the train NUMBER, no name, so those
-- orders land with a blank train_name even though other aggregators DID give us the
-- name for the same train. Train names are universal, public, and carry no PII, so
-- this catalog is GLOBAL: a name learned from any email fills in the gaps everywhere.

CREATE TABLE train_name_catalog (
    train_number VARCHAR(5)   PRIMARY KEY,
    train_name   VARCHAR(120) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed from the most frequently seen non-blank name per train number already stored
-- (ties broken by longest name, then lexically, for a deterministic result).
INSERT INTO train_name_catalog (train_number, train_name)
SELECT train_number, train_name
FROM (
    SELECT train_number,
           train_name,
           row_number() OVER (
               PARTITION BY train_number
               ORDER BY count(*) DESC, length(train_name) DESC, train_name ASC
           ) AS rn
    FROM irctc_order
    WHERE train_number IS NOT NULL AND btrim(train_number) <> ''
      AND train_name   IS NOT NULL AND btrim(train_name)   <> ''
    GROUP BY train_number, train_name
) ranked
WHERE rn = 1
ON CONFLICT (train_number) DO NOTHING;

-- Back-fill existing orders that have a number but no name, from the catalog.
UPDATE irctc_order o
SET train_name = c.train_name
FROM train_name_catalog c
WHERE o.train_number = c.train_number
  AND (o.train_name IS NULL OR btrim(o.train_name) = '');
