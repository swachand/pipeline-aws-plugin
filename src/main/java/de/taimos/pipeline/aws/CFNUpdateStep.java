/*
 * -
 * #%L
 * Pipeline: AWS Steps
 * %%
 * Copyright (C) 2016 Taimos GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package de.taimos.pipeline.aws;

import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.cloudformation.model.Parameter;

import de.taimos.pipeline.aws.cloudformation.CloudFormationStack;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.TaskListener;

public class CFNUpdateStep extends AbstractStepImpl {
	
	private final String stack;
	private final String file;
	private String[] params;
	private String[] keepParams;
	
	@DataBoundConstructor
	public CFNUpdateStep(String stack, String file) {
		this.stack = stack;
		this.file = file;
	}
	
	public String getStack() {
		return this.stack;
	}
	
	public String getFile() {
		return this.file;
	}
	
	public String[] getParams() {
		return this.params != null ? this.params.clone() : null;
	}
	
	@DataBoundSetter
	public void setParams(String[] params) {
		this.params = params.clone();
	}
	
	public String[] getKeepParams() {
		return this.keepParams != null ? this.keepParams.clone() : null;
	}
	
	@DataBoundSetter
	public void setKeepParams(String[] keepParams) {
		this.keepParams = keepParams.clone();
	}
	
	@Extension
	public static class DescriptorImpl extends AbstractStepDescriptorImpl {
		
		public DescriptorImpl() {
			super(Execution.class);
		}
		
		@Override
		public String getFunctionName() {
			return "cfnUpdate";
		}
		
		@Override
		public String getDisplayName() {
			return "Create or Update CloudFormation stack";
		}
	}
	
	public static class Execution extends AbstractStepExecutionImpl {
		
		@Inject
		private transient CFNUpdateStep step;
		@StepContextParameter
		private transient EnvVars envVars;
		@StepContextParameter
		private transient FilePath workspace;
		@StepContextParameter
		private transient TaskListener listener;
		
		@Override
		public boolean start() throws Exception {
			final String stack = this.step.getStack();
			final String file = this.step.getFile();
			final Collection<Parameter> params = this.parseParams(this.step.getParams());
			final Collection<Parameter> keepParams = this.parseKeepParams(this.step.getKeepParams());
			
			this.listener.getLogger().format("Updating/Creating CloudFormation stack %s %n", stack);
			
			new Thread("cfnUpdate-" + stack) {
				@Override
				public void run() {
					try {
						AmazonCloudFormationClient client = AWSClientFactory.create(AmazonCloudFormationClient.class, Execution.this.envVars);
						CloudFormationStack cfnStack = new CloudFormationStack(client, stack, Execution.this.listener);
						if (cfnStack.exists()) {
							ArrayList<Parameter> parameters = new ArrayList<>(params);
							parameters.addAll(keepParams);
							cfnStack.update(Execution.this.readTemplate(file), parameters);
						} else {
							cfnStack.create(Execution.this.readTemplate(file), params);
						}
						Execution.this.listener.getLogger().println("Stack update complete");
						Execution.this.getContext().onSuccess(cfnStack.describeOutputs());
					} catch (Exception e) {
						Execution.this.getContext().onFailure(e);
					}
				}
			}.start();
			return false;
		}
		
		private String readTemplate(String file) {
			FilePath child = this.workspace.child(file);
			try {
				return child.readToString();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		
		private Collection<Parameter> parseParams(String[] params) {
			Collection<Parameter> parameters = new ArrayList<>();
			if (params == null) {
				return parameters;
			}
			for (String param : params) {
				int i = param.indexOf("=");
				if (i < 0) {
					throw new RuntimeException("Missing = in param " + param);
				}
				String key = param.substring(0, i);
				String value = param.substring(i + 1);
				parameters.add(new Parameter().withParameterKey(key).withParameterValue(value));
			}
			return parameters;
		}
		
		private Collection<Parameter> parseKeepParams(String[] params) {
			Collection<Parameter> parameters = new ArrayList<>();
			if (params == null) {
				return parameters;
			}
			for (String param : params) {
				parameters.add(new Parameter().withParameterKey(param).withUsePreviousValue(true));
			}
			return parameters;
		}
		
		@Override
		public void stop(@Nonnull Throwable cause) throws Exception {
			//
		}
		
		private static final long serialVersionUID = 1L;
		
	}
	
}