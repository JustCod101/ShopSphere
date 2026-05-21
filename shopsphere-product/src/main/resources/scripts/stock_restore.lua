-- 库存原子回补（TCC-Cancel 的 Redis 子动作，契约 §4.3）
-- KEYS[1] = stock key（stock:product:{id}）
-- ARGV[1] = 回补数量
-- key 存在 → INCRBY；不存在 → SET 兜底（回补量是先前已扣量，恢复部分库存优于丢失）
-- 返回：回补后的库存值
if redis.call('EXISTS', KEYS[1]) == 1 then
    return redis.call('INCRBY', KEYS[1], ARGV[1])
else
    redis.call('SET', KEYS[1], ARGV[1])
    return tonumber(ARGV[1])
end
