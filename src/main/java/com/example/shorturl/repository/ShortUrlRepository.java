package com.example.shorturl.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.shorturl.model.ShortUrl;

public interface ShortUrlRepository extends MongoRepository<ShortUrl, String> {

    Optional<ShortUrl> findByCode(String code);

    List<ShortUrl> findByCodeIn(Collection<String> codes);
}
