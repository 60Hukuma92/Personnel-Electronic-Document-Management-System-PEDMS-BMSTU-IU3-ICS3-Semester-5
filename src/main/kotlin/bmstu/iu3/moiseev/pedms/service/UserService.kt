package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.entity.UserEntity
import bmstu.iu3.moiseev.pedms.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class UserService(private val userRepository: UserRepository) {
    fun getAllUsers(): List<UserEntity> { return userRepository.findAll()}

    fun createUser(user: UserEntity): UserEntity { return userRepository.save(user) }
}