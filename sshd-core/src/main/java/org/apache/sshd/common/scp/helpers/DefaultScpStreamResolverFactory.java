/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sshd.common.scp.helpers;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.sshd.common.scp.ScpFileOpener;
import org.apache.sshd.common.scp.ScpSourceStreamResolver;
import org.apache.sshd.common.scp.ScpStreamResolverFactory;
import org.apache.sshd.common.scp.ScpTargetStreamResolver;
import org.apache.sshd.common.util.logging.AbstractLoggingBean;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class DefaultScpStreamResolverFactory extends AbstractLoggingBean implements ScpStreamResolverFactory {
    public static final DefaultScpStreamResolverFactory INSTANCE = new DefaultScpStreamResolverFactory();

    public DefaultScpStreamResolverFactory() {
        super();
    }

    @Override
    public ScpSourceStreamResolver createScpSourceStreamResolver(Path path, ScpFileOpener opener) throws IOException {
        return new LocalFileScpSourceStreamResolver(path, opener);
    }

    @Override
    public ScpTargetStreamResolver createScpTargetStreamResolver(Path path, ScpFileOpener opener) throws IOException {
        return new LocalFileScpTargetStreamResolver(path, opener);
    }
}
