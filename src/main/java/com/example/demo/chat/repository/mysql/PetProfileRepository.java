package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.PetProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PetProfileRepository extends JpaRepository<PetProfile, Long> {
    List<PetProfile> findByNameContaining(String name);
    List<PetProfile> findBySpeciesContaining(String species);
}