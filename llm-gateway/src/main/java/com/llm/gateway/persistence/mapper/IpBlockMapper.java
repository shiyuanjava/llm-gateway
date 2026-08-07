package com.llm.gateway.persistence.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.gateway.persistence.entity.IpBlockEntity;

/** IP 封禁记录 Mapper。 */
public interface IpBlockMapper extends BaseMapper<IpBlockEntity> {

    /** 每个 IP 只保留最近一次封禁状态；并发触发时使用数据库原子 upsert 收敛。 */
    @Insert(
            """
            INSERT INTO ip_block_record
                (ip_address, block_source, reason, trigger_count, blocked_at, blocked_until, active)
            VALUES
                (#{record.ipAddress}, #{record.blockSource}, #{record.reason}, #{record.triggerCount},
                 #{record.blockedAt}, #{record.blockedUntil}, 1)
            ON DUPLICATE KEY UPDATE
                block_source = VALUES(block_source),
                reason = VALUES(reason),
                trigger_count = VALUES(trigger_count),
                blocked_at = VALUES(blocked_at),
                blocked_until = VALUES(blocked_until),
                active = 1,
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("record") IpBlockEntity record);

    /** 把已经到期的临时封禁转为非活动状态。 */
    @Update(
            """
            UPDATE ip_block_record
            SET active = 0, updated_at = CURRENT_TIMESTAMP
            WHERE active = 1 AND blocked_until IS NOT NULL AND blocked_until <= #{now}
            """)
    int expireElapsed(@Param("now") LocalDateTime now);

    /** 只把此刻确实已经到期的指定 IP 解封，避免旧缓存误清除并发创建的新封禁。 */
    @Update(
            """
            UPDATE ip_block_record
            SET active = 0, updated_at = CURRENT_TIMESTAMP
            WHERE ip_address = #{ipAddress}
              AND active = 1
              AND blocked_until IS NOT NULL
              AND blocked_until <= #{now}
            """)
    int expireAddressIfElapsed(@Param("ipAddress") String ipAddress, @Param("now") LocalDateTime now);
}
