/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.security.auth.provider.jdbc;

import static org.wildfly.security._private.ElytronMessages.log;

import org.wildfly.security.auth.provider.jdbc.mapper.PasswordKeyMapper;
import org.wildfly.security.authz.AuthorizationIdentity;
import org.wildfly.security.auth.server.CredentialSupport;
import org.wildfly.security.auth.server.RealmIdentity;
import org.wildfly.security.auth.server.RealmUnavailableException;
import org.wildfly.security.auth.server.SecurityRealm;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.interfaces.ClearPassword;

import javax.sql.DataSource;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Security realm implementation backed by a database.
 *
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class JdbcSecurityRealm implements SecurityRealm {

    private final List<QueryConfiguration> queryConfiguration;

    public static JdbcSecurityRealmBuilder builder() {
        return new JdbcSecurityRealmBuilder();
    }

    JdbcSecurityRealm(List<QueryConfiguration> queryConfiguration) {
        this.queryConfiguration = queryConfiguration;
    }

    @Override
    public RealmIdentity createRealmIdentity(final String name) throws RealmUnavailableException {
        return new JdbcRealmIdentity(name);
    }

    @Override
    public CredentialSupport getCredentialSupport(Class<?> credentialType) throws RealmUnavailableException {
        for (QueryConfiguration configuration : this.queryConfiguration) {
            for (ColumnMapper mapper : configuration.getColumnMappers()) {
                if (KeyMapper.class.isInstance(mapper)) {
                    KeyMapper keyMapper = (KeyMapper) mapper;

                    if (credentialType.equals(keyMapper.getKeyType())) {
                        // by default, all credential types are supported if they have a corresponding mapper.
                        // however, we don't know if an account or realm identity has a specific credential or not.
                        return CredentialSupport.UNKNOWN;
                    }
                }
            }
        }

        return CredentialSupport.UNSUPPORTED;
    }

    private class JdbcRealmIdentity implements RealmIdentity {

        private final String name;

        public JdbcRealmIdentity(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public CredentialSupport getCredentialSupport(Class<?> credentialType) throws RealmUnavailableException {
            for (QueryConfiguration configuration : JdbcSecurityRealm.this.queryConfiguration) {
                for (ColumnMapper mapper : configuration.getColumnMappers()) {
                    if (KeyMapper.class.isInstance(mapper)) {
                        KeyMapper keyMapper = (KeyMapper) mapper;

                        if (keyMapper.getKeyType().isAssignableFrom(credentialType)) {
                            return executeAuthenticationQuery(configuration, keyMapper::getCredentialSupport);
                        }
                    }
                }
            }

            return CredentialSupport.UNSUPPORTED;
        }

        @Override
        public <C> C getCredential(Class<C> credentialType) throws RealmUnavailableException {
            for (QueryConfiguration configuration : JdbcSecurityRealm.this.queryConfiguration) {
                for (ColumnMapper mapper : configuration.getColumnMappers()) {
                    if (KeyMapper.class.isInstance(mapper)) {
                        KeyMapper keyMapper = (KeyMapper) mapper;

                        if (keyMapper.getKeyType().isAssignableFrom(credentialType)) {
                            return executeAuthenticationQuery(configuration, resultSet -> (C) keyMapper.map(resultSet));
                        }
                    }
                }
            }

            return null;
        }

        @Override
        public boolean verifyCredential(Object credential) throws RealmUnavailableException {
            if (credential == null) {
                return false;
            }

            for (QueryConfiguration configuration : JdbcSecurityRealm.this.queryConfiguration) {
                for (ColumnMapper mapper : configuration.getColumnMappers()) {
                    if (KeyMapper.class.isInstance(mapper)) {
                        KeyMapper credentialMapper = (KeyMapper) mapper;

                        if (Password.class.isAssignableFrom(credentialMapper.getKeyType())) {
                            PasswordKeyMapper passwordMapper = (PasswordKeyMapper) mapper;
                            return verifyPassword(configuration, passwordMapper, credential);
                        }
                    }
                }
            }

            return false;
        }

        public boolean exists() throws RealmUnavailableException {
            return true;
        }

        @Override
        public AuthorizationIdentity getAuthorizationIdentity() throws RealmUnavailableException {
            return new JdbcAuthorizationIdentity(name);
        }

        private boolean verifyPassword(QueryConfiguration configuration, PasswordKeyMapper passwordMapper, Object givenCredential) {
            Object credential = executeAuthenticationQuery(configuration, passwordMapper::map);

            String algorithm = passwordMapper.getAlgorithm();

            try {
                if (Password.class.isInstance(credential)) {
                    PasswordFactory passwordFactory = getPasswordFactory(algorithm);
                    char[] guessCredentialChars;

                    if (String.class.equals(givenCredential.getClass())) {
                        guessCredentialChars = givenCredential.toString().toCharArray();
                    } else if (char[].class.equals(givenCredential.getClass())) {
                        guessCredentialChars = (char[]) givenCredential;
                    } else if (ClearPassword.class.isInstance(givenCredential)) {
                        guessCredentialChars = ((ClearPassword) givenCredential).getPassword();
                    } else {
                        throw log.passwordBasedCredentialsMustBeStringCharsOrClearPassword();
                    }

                    return passwordFactory.verify((Password) credential, guessCredentialChars);
                }
            } catch (InvalidKeyException e) {
                throw log.invalidPasswordKeyForAlgorithm(algorithm, e);
            }

            return false;
        }

        private PasswordFactory getPasswordFactory(String algorithm) {
            try {
                return PasswordFactory.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw log.couldNotObtainPasswordFactoryForAlgorithm(algorithm, e);
            }
        }

        private Connection getConnection(QueryConfiguration configuration) {
            Connection connection;
            try {
                DataSource dataSource = configuration.getDataSource();
                connection = dataSource.getConnection();
            } catch (Exception e) {
                throw log.couldNotOpenConnection(e);
            }
            return connection;
        }

        private void safeClose(AutoCloseable closeable) {
            try {
                if (closeable != null) {
                    closeable.close();
                }
            } catch (Exception ignore) {

            }
        }

        private <E> E executeAuthenticationQuery(QueryConfiguration configuration, ResultSetCallback<E> resultSetCallback) {
            String sql = configuration.getSql();
            Connection connection = getConnection(configuration);
            PreparedStatement preparedStatement = null;
            ResultSet resultSet = null;

            try {
                preparedStatement = connection.prepareStatement(sql);
                preparedStatement.setString(1, getName());
                resultSet = preparedStatement.executeQuery();
                return resultSetCallback.handle(resultSet);
            } catch (SQLException e) {
                throw log.couldNotExecuteQuery(sql, e);
            } catch (Exception e) {
                throw log.unexpectedErrorWhenProcessingAuthenticationQuery(sql, e);
            } finally {
                safeClose(resultSet);
                safeClose(preparedStatement);
                safeClose(connection);
            }
        }

        private class JdbcAuthorizationIdentity implements AuthorizationIdentity {

            private String name;

            JdbcAuthorizationIdentity(final String name) {
                this.name = name;
            }
        }
    }

    private interface ResultSetCallback<E> {
         E handle(ResultSet resultSet);
    }
}
