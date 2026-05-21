-- 库存原子预扣（TCC-Try 的 Redis 子动作，契约 §4.3）
-- KEYS[1] = stock key（stock:product:{id}）
-- ARGV[1] = 扣减数量
-- 返回：>=0 扣减后剩余库存（成功） / -1 库存不足 / -2 key 不存在
-- GET + 判断 + DECRBY 三步在脚本内原子执行，杜绝超卖。
local cur = tonumber(redis.call('GET', KEYS[1]))
if cur == nil then
    return -2
end
if cur < tonumber(ARGV[1]) then
    return -1
end
return redis.call('DECRBY', KEYS[1], ARGV[1])
