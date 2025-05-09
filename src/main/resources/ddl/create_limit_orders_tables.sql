-- Tabela dla zleceń limit
CREATE TABLE IF NOT EXISTS limit_orders (
    id SERIAL PRIMARY KEY,
    order_id VARCHAR(50) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    quantity VARCHAR(30) NOT NULL,
    entry_price VARCHAR(30) NOT NULL,
    stop_loss VARCHAR(30),
    leverage INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    filled_at TIMESTAMP WITH TIME ZONE,
    last_checked_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL
);

-- Tabela dla take-profits zlecenia limit
CREATE TABLE IF NOT EXISTS limit_order_take_profits (
    id SERIAL PRIMARY KEY,
    limit_order_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    price VARCHAR(30) NOT NULL,
    processed BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT fk_limit_order FOREIGN KEY (limit_order_id) REFERENCES limit_orders(id) ON DELETE CASCADE
);

-- Indeksy dla optymalizacji
CREATE INDEX IF NOT EXISTS idx_limit_orders_order_id ON limit_orders(order_id);
CREATE INDEX IF NOT EXISTS idx_limit_orders_status ON limit_orders(status);
CREATE INDEX IF NOT EXISTS idx_limit_orders_symbol ON limit_orders(symbol);
CREATE INDEX IF NOT EXISTS idx_limit_order_take_profits_limit_order_id ON limit_order_take_profits(limit_order_id);
CREATE INDEX IF NOT EXISTS idx_limit_order_take_profits_processed ON limit_order_take_profits(processed); 