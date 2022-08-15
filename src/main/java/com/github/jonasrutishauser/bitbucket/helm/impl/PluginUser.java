package com.github.jonasrutishauser.bitbucket.helm.impl;

import javax.inject.Inject;
import javax.inject.Named;

import com.atlassian.bitbucket.avatar.SimpleAvatarSupplier;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.EscalatedSecurityContext;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.bitbucket.user.ServiceUser;
import com.atlassian.bitbucket.user.ServiceUserCreateRequest;
import com.atlassian.bitbucket.user.ServiceUserUpdateRequest;
import com.atlassian.bitbucket.user.UserAdminService;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

@Named
public class PluginUser {

    private static final String DISPLAY_NAME = "Helm Templater";
    private static final String AVATAR_RESOURCE = "/images/pluginLogo.png";

    private final UserService userService;
    private final UserAdminService userAdminService;
    private final SecurityService securityService;
    private final String userName;
    private ServiceUser user;

    @Inject
    public PluginUser(@ComponentImport UserService userService, @ComponentImport UserAdminService userAdminService,
            @ComponentImport SecurityService securityService) {
        this.userService = userService;
        this.userAdminService = userAdminService;
        this.securityService = securityService;
        this.userName = "helm-pr-bitbucket-plugin:user";
    }

    public ApplicationUser getUser() {
        if (user == null) {
            securityService.anonymously("create or update plugin user").withPermission(Permission.SYS_ADMIN)
                    .call(this::createOrUpdateUser);
        }
        return user;
    }

    public EscalatedSecurityContext impersonating(String reason) {
        return securityService.impersonating(getUser(), reason);
    }

    private ApplicationUser createOrUpdateUser() {
        user = userService.getServiceUserByName(userName, true);
        if (user == null) {
            user = userAdminService.createServiceUser(new ServiceUserCreateRequest.Builder() //
                    .name(userName) //
                    .displayName(DISPLAY_NAME) //
                    .active(true) //
                    .build());
        } else {
            user = userAdminService.updateServiceUser(new ServiceUserUpdateRequest.Builder(user) //
                    .displayName(DISPLAY_NAME) //
                    .active(true) //
                    .build());
        }
        userService.updateAvatar(user, new SimpleAvatarSupplier(getClass().getResourceAsStream(AVATAR_RESOURCE)));
        return user;
    }

}
