package com.examprep.data.repository;

import com.examprep.data.entity.QueryHistory;

import java.util.List;
import java.util.UUID;

public interface QueryHistoryRepositoryCustom {
    List<QueryHistory> findByChatIdExcludingEmbedding(UUID chatId, int limit);
}

