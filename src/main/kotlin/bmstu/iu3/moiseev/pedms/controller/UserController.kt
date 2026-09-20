package bmstu.iu3.moiseev.pedms.controller

import bmstu.iu3.moiseev.pedms.entity.UserEntity
import bmstu.iu3.moiseev.pedms.service.UserService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping

@Controller
class UserController(private val userService: UserService) {
    @GetMapping("/users")
    fun getUsers(model: Model): String {
        model.addAttribute("users", userService.getAllUsers())
        return "users"
    }

    @GetMapping("/users/create")
    fun showCreateForm(model: Model): String {
        model.addAttribute("user", UserEntity())
        return "create-user"
    }

    @PostMapping("/users")
    fun createUser(@ModelAttribute user: UserEntity): String {
        userService.createUser(user)
        return "redirect:/users"
    }
}