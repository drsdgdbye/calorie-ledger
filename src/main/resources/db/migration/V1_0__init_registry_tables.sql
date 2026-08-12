CREATE TABLE users (
                       id BIGSERIAL PRIMARY KEY,
                       username TEXT NOT NULL UNIQUE,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TYPE product_unit AS ENUM ('GRAM', 'ML');

CREATE TABLE products (
                          id BIGSERIAL PRIMARY KEY,
                          user_id BIGINT NOT NULL REFERENCES users(id),
                          name TEXT NOT NULL,
                          category TEXT,
                          unit product_unit NOT NULL,
                          calories_per_100 INTEGER NOT NULL CHECK (calories_per_100 >= 0),
                          protein_per_100  INTEGER NOT NULL CHECK (protein_per_100 >= 0),
                          fat_per_100     INTEGER NOT NULL CHECK (fat_per_100     >= 0),
                          carbs_per_100    INTEGER NOT NULL CHECK (carbs_per_100    >= 0),
                          is_archived BOOLEAN NOT NULL DEFAULT false,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_products_search   ON products (user_id, is_archived, lower(name) text_pattern_ops);
CREATE INDEX idx_products_category ON products (user_id, category);

CREATE TABLE dishes (
                        id BIGSERIAL PRIMARY KEY,
                        user_id BIGINT NOT NULL REFERENCES users(id),
                        name TEXT NOT NULL,
                        cooked_weight_grams INTEGER NOT NULL CHECK (cooked_weight_grams > 0),
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dishes_search ON dishes (user_id, lower(name) text_pattern_ops);

CREATE TABLE dish_ingredients (
                                  id BIGSERIAL PRIMARY KEY,
                                  dish_id BIGINT NOT NULL REFERENCES dishes(id) ON DELETE CASCADE,
                                  product_id BIGINT NOT NULL REFERENCES products(id),
                                  quantity INTEGER NOT NULL CHECK (quantity > 0),
                                  position INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_dish_ingredients_dish    ON dish_ingredients (dish_id);
CREATE INDEX idx_dish_ingredients_product ON dish_ingredients (product_id);