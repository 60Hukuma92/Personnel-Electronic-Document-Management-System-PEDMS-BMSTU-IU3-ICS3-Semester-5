package bmstu.iu3.moiseev.pedms.config

import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.DisabledException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

/**
 * Настройка аутентификации и разграничения доступа (FR140, TR030, TR050).
 *
 * Требования, закрываемые этим компонентом:
 *   • вход в систему по логину (адрес электронной почты) и паролю (UC030);
 *   • разграничение прав по четырём ролям: сотрудник, руководитель,
 *     сотрудник ОК, администратор (FR140);
 *   • хранение паролей только в виде BCrypt-хэшей (TR030);
 *   • защита от подделки межсайтовых запросов — CSRF включён по умолчанию (TR050);
 *   • защита от внедрения SQL-кода — за счёт параметризованных запросов
 *     Spring Data JPA, и от XSS — за счёт экранирования Thymeleaf (TR050).
 */
@Configuration
class SecurityConfig {

    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    /** Кодировщик паролей: BCrypt с автоматически генерируемой солью. */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /**
     * Загрузка учётной записи по адресу электронной почты.
     *
     * Заблокированные и удалённые учётные записи не могут войти в систему.
     */
    @Bean
    fun userDetailsService(userRepository: AppUserRepository): UserDetailsService =
        UserDetailsService { email ->
            val user = userRepository.findByEmailIgnoreCase(email)
                ?: throw UsernameNotFoundException("Пользователь с адресом «$email» не найден")

            if (!user.canLogin) {
                log.warn("Попытка входа заблокированной учётной записи: {}", email)
                throw DisabledException("Учётная запись заблокирована. Обратитесь к сотруднику отдела кадров.")
            }

            PedmsUserDetails(
                userId = user.id ?: 0,
                email = user.email,
                fullName = user.fullName,
                passwordHash = user.passwordHash,
                role = user.role.name,
                enabled = user.canLogin
            )
        }

    /**
     * Цепочка фильтров безопасности.
     *
     * Порядок разделов меню повторяет дерево функций (рисунок 2.3 записки):
     * разделы справочников и администрирования доступны только сотрудникам
     * отдела кадров и администратору.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { registry ->
                registry
                    // Страница входа и статические ресурсы доступны без авторизации
                    .requestMatchers(
                        "/login",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/error"
                    ).permitAll()
                    // Главная страница этапа 1 — техническая
                    .requestMatchers("/").permitAll()
                    // Администрирование учётных записей и правил маршрутизации
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    // Справочники: виды документов, должности, подразделения
                    .requestMatchers("/references/**").hasAnyRole("HR", "ADMIN")
                    // Отчёты и архив — сотрудникам ОК и администратору
                    .requestMatchers("/reports/**", "/archive/**").hasAnyRole("HR", "ADMIN")
                    // Листы ознакомления формирует сотрудник ОК,
                    // но подтверждать ознакомление может любой сотрудник
                    .requestMatchers("/acquaintance/create/**").hasAnyRole("HR", "ADMIN")
                    .requestMatchers("/acquaintance/**").authenticated()
                    // Согласование документов — руководителю, сотруднику ОК и администратору
                    .requestMatchers("/approval/**").hasAnyRole("MANAGER", "HR", "ADMIN")
                    // Остальные страницы доступны любому авторизованному пользователю
                    .anyRequest().authenticated()
            }
            .formLogin { form ->
                form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .usernameParameter("email")
                    .passwordParameter("password")
                    .defaultSuccessUrl("/dashboard", true)
                    .failureUrl("/login?error")
                    .permitAll()
            }
            .logout { logout ->
                logout
                    .logoutRequestMatcher(AntPathRequestMatcher("/logout"))
                    .logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll()
            }
            .exceptionHandling { handling ->
                handling.accessDeniedPage("/error/403")
            }
        return http.build()
    }
}
