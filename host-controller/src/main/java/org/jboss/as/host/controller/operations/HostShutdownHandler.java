/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.jboss.as.host.controller.operations;


import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SHUTDOWN;

import java.util.EnumSet;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.access.Action;
import org.jboss.as.controller.access.AuthorizationResult;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.as.domain.controller.DomainController;
import org.jboss.as.host.controller.descriptions.HostResolver;
import org.jboss.as.host.controller.logging.HostControllerLogger;
import org.jboss.as.process.ExitCodes;
import org.jboss.as.server.SystemExiter;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Stops a host.
 *
 * @author Kabir Khan
 */
public class HostShutdownHandler implements OperationStepHandler {

    public static final String OPERATION_NAME = "shutdown";

    private final DomainController domainController;

    private static final AttributeDefinition RESTART = SimpleAttributeDefinitionBuilder.create(ModelDescriptionConstants.RESTART, ModelType.BOOLEAN, true)
            .setAllowNull(true)
            .build();
    public static final OperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(OPERATION_NAME, HostResolver.getResolver("host"))
            .addParameter(RESTART)
            .withFlag(OperationEntry.Flag.HOST_CONTROLLER_ONLY)
            .setRuntimeOnly()
            .build();

    /**
     * Create the ServerAddHandler
     */
    public HostShutdownHandler(final DomainController domainController) {
        this.domainController = domainController;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
        final boolean restart = RESTART.validateOperation(operation).asBoolean(false);
        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                // WFLY-2741 -- DO NOT call context.getServiceRegistry(true) as that will trigger blocking for
                // service container stability and one use case for this op is to recover from a
                // messed up service container from a previous op. Instead just ask for authorization.
                // Note that we already have the exclusive lock, so we are just skipping waiting for stability.
                // If another op that is a step in a composite step with this op needs to modify the container
                // it will have to wait for container stability, so skipping this only matters for the case
                // where this step is the only runtime change.
//                context.getServiceRegistry(true);
                AuthorizationResult authorizationResult = context.authorize(operation, EnumSet.of(Action.ActionEffect.WRITE_RUNTIME));
                if (authorizationResult.getDecision() == AuthorizationResult.Decision.DENY) {
                    throw ControllerLogger.ACCESS_LOGGER.unauthorized(operation.get(OP).asString(),
                            PathAddress.pathAddress(operation.get(OP_ADDR)), authorizationResult.getExplanation());
                }
                SystemExiter.logBeforeExit(new SystemExiter.ExitLogger() {
                    @Override
                    public void logExit() {
                        HostControllerLogger.ROOT_LOGGER.shuttingDownInResponseToManagementRequest(getOperationName(operation));
                    }
                });
                if (restart) {
                    //Add the exit code so that we get respawned
                    domainController.stopLocalHost(ExitCodes.RESTART_PROCESS_FROM_STARTUP_SCRIPT);
                } else {
                    domainController.stopLocalHost();
                }
                context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
            }
        }, OperationContext.Stage.RUNTIME);
    }

    private static String getOperationName(ModelNode op) {
        return op.hasDefined(OP) ? op.get(OP).asString() : SHUTDOWN;
    }
}
