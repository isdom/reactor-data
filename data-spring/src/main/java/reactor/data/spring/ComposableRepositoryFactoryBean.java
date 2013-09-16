package reactor.data.spring;

import static org.springframework.core.GenericTypeResolver.*;
import static org.springframework.util.ReflectionUtils.*;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.util.ReflectionUtils;
import reactor.core.Environment;
import reactor.core.HashWheelTimer;
import reactor.core.composable.Deferred;
import reactor.core.composable.Stream;
import reactor.core.composable.spec.Streams;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class ComposableRepositoryFactoryBean<R extends ComposableCrudRepository<T, ID>, T, ID extends Serializable>
		implements FactoryBean<R>,
		           ApplicationListener<ContextRefreshedEvent> {

	private final Environment           env;
	private final String                dispatcher;
	private final HashWheelTimer        timer;
	private final Class<R>              repositoryType;
	private       Class<? extends T>    domainType;
	private       ListableBeanFactory   beanFactory;
	private       Repositories          repositories;
	private       CrudRepository<T, ID> delegateRepository;
	private       R                     composableRepository;

	@SuppressWarnings("unchecked")
	public ComposableRepositoryFactoryBean(Environment env,
	                                       String dispatcher,
	                                       HashWheelTimer timer,
	                                       Class<R> repositoryType) {
		this.env = env;
		this.dispatcher = dispatcher;
		this.timer = timer;
		this.repositoryType = repositoryType;
		for(Class<?> intfType : repositoryType.getInterfaces()) {
			if(!ComposableRepository.class.isAssignableFrom(intfType)) {
				continue;
			}
			Class<?>[] types = resolveTypeArguments(repositoryType, ComposableRepository.class);
			this.domainType = (Class<? extends T>)types[0];
			break;
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onApplicationEvent(ContextRefreshedEvent event) {
		if(null != composableRepository) {
			return;
		}
		this.beanFactory = event.getApplicationContext();
		repositories = new Repositories(this.beanFactory);
		if(null != (delegateRepository = (CrudRepository<T, ID>)repositories.getRepositoryFor(domainType))) {
			SimpleComposableCrudRepository<T, ID> repo = new SimpleComposableCrudRepository<>(env,
			                                                                                  dispatcher,
			                                                                                  timer,
			                                                                                  delegateRepository);

			ProxyFactory proxyFactory = new ProxyFactory(repo);
			proxyFactory.addInterface(repositoryType);
			proxyFactory.addInterface(ComposableRepository.class);

			proxyFactory.addAdvice(new QueryMethodExecutor<>(repositoryType));

			composableRepository = (R)proxyFactory.getProxy();
		}
	}

	@Override
	public R getObject() throws Exception {
		return composableRepository;
	}

	@Override
	public Class<R> getObjectType() {
		return repositoryType;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	private class QueryMethodExecutor<R extends ComposableCrudRepository<T, ID>, T, ID extends Serializable>
			implements MethodInterceptor {
		private final Map<String, Method>     crudMethods  = new HashMap<>();
		private final Map<String, Method>     queryMethods = new HashMap<>();
		private final Map<String, Class<?>[]> paramTypes   = new HashMap<>();

		private QueryMethodExecutor(Class<R> composableRepositoryType) {
			doWithMethods(
					composableRepositoryType,
					new ReflectionUtils.MethodCallback() {
						@Override
						public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
							String name = method.toGenericString();
							Class<?>[] paramTypes = method.getParameterTypes();
							QueryMethodExecutor.this.paramTypes.put(name, paramTypes);
						}
					},
					method -> Object.class != method.getDeclaringClass() && !method.getName().contains("$")
			);
		}

		@SuppressWarnings("unchecked")
		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {
			String name = invocation.getMethod().toGenericString();
			Class<?>[] paramTypes = this.paramTypes.get(name);

			try {
				Method m;
				if(null == (m = crudMethods.get(name))) {
					if(null != (m = invocation.getThis().getClass().getDeclaredMethod(invocation.getMethod().getName(),
					                                                                  paramTypes))) {
						crudMethods.put(name, m);
					}
				}
				if(null != m) {
					return m.invoke(invocation.getThis(), invocation.getArguments());
				}
			} catch(Exception e) {
				if(NoSuchMethodException.class.isAssignableFrom(e.getClass())) {
					// this is probably a finder method
					Method m;
					if(null == (m = queryMethods.get(name))) {
						if(null != (m = delegateRepository.getClass().getDeclaredMethod(invocation.getMethod().getName(),
						                                                                paramTypes))) {
							queryMethods.put(name, m);
						}
					}
					if(null != m) {
						Deferred<Object, Stream<Object>> d = Streams.<Object>defer()
						                                            .env(env)
						                                            .dispatcher(dispatcher)
						                                            .get();

						final Method queryMethod = m;
						timer.submit(l -> {
							Object returnVal = ReflectionUtils.invokeMethod(queryMethod,
							                                                delegateRepository,
							                                                invocation.getArguments());
							d.accept(returnVal);
						});

						return d.compose();
					}
				}

				throw e;
			}

			throw new NoSuchMethodException(name);
		}
	}

}
