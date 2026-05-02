package au.com.equifax.cicddemo.controller;

import java.net.URI;
import java.util.Collection;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import au.com.equifax.cicddemo.domain.User;
import au.com.equifax.cicddemo.service.UserService;

@RestController("userController")
public class UserController {

    private UserService service;

    public UserController(UserService param){
        this.service=param;
    }

    @GetMapping("/users")
    public Collection<User> retrieveAllUsers() {
        return service.getUsers();
    }
    @GetMapping("/users/{id}")
    public User retrieveStudent(@PathVariable long id) {
        Optional<User> student = service.findById(id);
        if (student.isPresent()) {
            return student.get();
        }
        return null;
    }
    @PostMapping("/users")
    public ResponseEntity<Object> createStudent(@RequestBody User user) {
        service.save(user);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(user.getId()).toUri();

        return ResponseEntity.created(location).build();

    }

}
