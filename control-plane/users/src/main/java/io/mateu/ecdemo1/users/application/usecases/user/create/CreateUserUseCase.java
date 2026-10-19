package io.mateu.ecdemo1.users.application.usecases.user.create;

import io.mateu.ecdemo1.users.application.out.UserRepository;
import io.mateu.ecdemo1.users.application.usecases.user.identity.IdentityOutboxAppender;
import io.mateu.ecdemo1.users.domain.aggregates.role.vo.RoleId;
import io.mateu.ecdemo1.users.domain.aggregates.shared.vo.Email;
import io.mateu.ecdemo1.users.domain.aggregates.shared.vo.Name;
import io.mateu.ecdemo1.users.domain.aggregates.user.User;
import io.mateu.ecdemo1.users.domain.aggregates.user.vo.UserId;
import io.mateu.ecdemo1.users.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateUserUseCase {

    final UserRepository repository;
    final IdentityOutboxAppender identityOutboxAppender;

    /**
     * Creates a user, and only creates: the id is the caller's (the username), and the entity is not
     * Persistable, so a save with an id that already exists would merge over that user — overwriting
     * its name, groups and roles and pushing the result to Keycloak — under a button that says
     * "create". Refused instead, with the reason in words.
     */
    @Transactional
    public void handle(CreateUserCommand command) {
        var id = new UserId(command.id());
        if (repository.findById(id).isPresent()) {
            throw new IllegalArgumentException("A user with id " + command.id() + " already exists");
        }
        var user = User.of(
                id,
                new Name(command.name()),
                new Email(command.email()),
                // never null: CreateUserCommand turns a missing list into an empty one
                command.groupIds().stream().map(UserGroupId::new).toList(),
                command.roleIds().stream().map(RoleId::new).toList()
        );
        repository.save(user);
        // Same transaction as the save: the "tell Keycloak" intent commits with the user or not at
        // all, and the relay delivers it afterwards. See IdentityOutboxAppender.
        identityOutboxAppender.drain(user);
    }

}
