package com.simpleec.backendJob.handler.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.backendJob.handler.AbstractEventHandler;
import com.simpleec.common.util.RedisKeyUtil;
import com.simpleec.core.service.DailyStatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * Statistics recalculation handler — driven by Heartbeat scheduler.
 *
 * Triggered every 5 minutes (minute % 5 == 1) via task.backend topic.
 * Reads dirty markers from Redis sorted set, recalculates stats for each
 * changed (merchantId, platformId, channelId, date) combination.
 *
 * The sorted set acts as natural deduplication: 1000 orders on the same
 * channel in 5 minutes still produces only 1 aggregate query.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsRecalcHandler extends AbstractEventHandler {

    private final StringRedisTemplate redisTemplate;
    private final DailyStatisticsService dailyStatisticsService;

    @Override
    public String getTaskType() {
        return "STATS_RECALC";
    }

    @Override
    protected void processReport(JsonNode event, String merchantId, String timestamp) {
        long nowMs = System.currentTimeMillis();

        Set<String> dirtyMembers = redisTemplate.opsForZSet()
                .rangeByScore(RedisKeyUtil.STATS_DIRTY_KEY, 0, nowMs);

        if (dirtyMembers == null || dirtyMembers.isEmpty()) {
            log.debug("No dirty stats entries to process");
            return;
        }

        log.info("Processing {} dirty stats entries", dirtyMembers.size());

        for (String member : dirtyMembers) {
            try {
                // member format: {merchantId}:{platformId}:{channelId}:{statDate}
                String[] parts = member.split(":", 4);
                if (parts.length != 4) {
                    log.warn("Skipping invalid dirty member: {}", member);
                    redisTemplate.opsForZSet().remove(RedisKeyUtil.STATS_DIRTY_KEY, member);
                    continue;
                }

                String mId = parts[0];
                String platformId = parts[1];
                String channelId = parts[2];
                LocalDate statDate = LocalDate.parse(parts[3]);

                dailyStatisticsService.recalculate(mId, platformId, channelId, statDate);
                redisTemplate.opsForZSet().remove(RedisKeyUtil.STATS_DIRTY_KEY, member);

            } catch (Exception e) {
                log.error("Error processing dirty stats member: {}", member, e);
                // Leave in set — will be retried next cycle
            }
        }
    }
}
