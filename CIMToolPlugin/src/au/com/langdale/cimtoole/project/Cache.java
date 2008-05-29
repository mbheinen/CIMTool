/*
 * This software is Copyright 2005,2006,2007,2008 Langdale Consultants.
 * Langdale Consultants can be contacted at: http://www.langdale.com.au
 */
package au.com.langdale.cimtoole.project;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import au.com.langdale.cimtoole.CIMToolPlugin;

import com.hp.hpl.jena.graph.compose.MultiUnion;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.ontology.OntModelSpec;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
/**
 * A cache of ontology models. 
 */
public class Cache extends Info {
	/**
	 * The session property used to cache models. 
	 */
	public static final QualifiedName ONTMODEL = new QualifiedName(CIMToolPlugin.PLUGIN_ID, "ontmodel");
	
	private ListenerList listeners = new ListenerList();

	/**
	 * Clients provide this interface to be notified of cache changes.
	 */
	public interface CacheListener {
		/**
		 * Indicates that a cached model is available. This occurs
		 * after parsing is complete, which is in turn triggered by
		 * the initial access to the model or a change in the underlying 
		 * model file(s).
		 *   
		 * @param key: the file or folder for the model
		 */
		public void modelCached(IResource key);
		/**
		 * Indicates that a model has been removed from the cache
		 * because the model file has been deleted. 
		 * @param key: the file for the model
		 */
		public void modelDropped(IResource key);
	}

	public Cache() {
		ResourcesPlugin.getWorkspace().addResourceChangeListener(
				new ResourceListener(), IResourceChangeEvent.POST_CHANGE);
	}
	/**
	 * Return a cached model, if cached.  Otherwise initiate parsing to create and cache the model. 
	 * @param file: the file for the model 
	 * @return: the model cached for the given file 
	 * or null if the model is not currently cached
	 * or the file is the wrong type as indicated by <code>Info.isParsable()</code>.
	 */
	public OntModel getOntology( IFile file ) {
		if( ! isParseable(file))
			return null;

		return (OntModel) getCached(new ParseWorker(file, false));
	}
	/**
	 * Return the union of all models in a folder, if all are cached.
	 * Otherwise initiate parsing to create and cache the missing models.
	 * Each model corresponds to a file in the folder for which <code>Info.isParsable()</code> is true.  
	 * @param folder: the folder containing model files.
	 * @return: the union of the cached models or null.
	 */
	public OntModel getMergedOntology( IFolder folder ) {
		DynamicMergedModel merger = (DynamicMergedModel) getCached(new MergeWorker(folder, false));
		if( merger == null )
			return null;
		return merger.getBaseModel();
	}
	/**
	 * Return a cached model, parse and cache the model first if necessary.
	 * @param file: the file for the model
	 * @return: the model 
	 * @throws CoreException
	 */
	public OntModel getOntologyWait( IFile file ) throws CoreException {
		if( ! isParseable(file))
			throw error(file + " is not an ontology.");

		return (OntModel) getCachedWait(new ParseWorker(file, false));
	}
	/**
	 * Return a union of cached models, parse and cache any missing models first if necessary.
	 * @param folder: the folder containing model files
	 * @return: the union of models
	 * @throws CoreException
	 */
	public OntModel getMergedOntologyWait( IFolder folder ) throws CoreException {
		DynamicMergedModel merger = (DynamicMergedModel) getCachedWait(new MergeWorker(folder, false));
		if( merger == null )
			return null;
		return merger.getBaseModel();
	}
	/**
	 * Register to receive cache change notifications.
	 * @param listener: the receiver.
	 */
	public void addCacheListener(CacheListener listener) {
		listeners.add(listener);
	}
	/**
	 * Deregister to stop receiving cache notifications.
	 * @param listener: the receiver.
	 */
	public void removeCacheListener(CacheListener listener) {
		listeners.remove(listener);
	}

	private Object getCached(CacheWorker worker) {
		if( ! worker.getResource().exists())
			return null;
		
		try {
			Object raw = worker.getCached();
			if( raw != null)
				return raw;
		} catch (CoreException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}

		runJob(worker);
		return null;
	}
	
	private Object getCachedWait(CacheWorker worker) throws CoreException {
		Object raw = worker.getCached();
		if( raw != null )
			return raw;
		runWait(worker);
		return worker.getCached();
	}
	
	private void runJob(CacheWorker worker) {
		Job job = new CacheJob(worker);
		job.schedule();
	}
	
	private void runWait(CacheWorker worker) throws CoreException {
		ResourcesPlugin.getWorkspace().run(worker, worker.getResource(), 0, null);
	}
	
	private void fireModelCached(IResource key) {
		Object[] current = listeners.getListeners();
		for (int ix = 0; ix < current.length; ix++) {
			CacheListener listener = (CacheListener) current[ix];
			listener.modelCached(key);
		}
	}

	private void fireModelDropped(IResource key) {
		Object[] current = listeners.getListeners();
		for (int ix = 0; ix < current.length; ix++) {
			CacheListener listener = (CacheListener) current[ix];
			listener.modelDropped(key);
		}
	}
	
	private class CacheJob extends Job {
		private CacheWorker worker;
		
		public CacheJob(CacheWorker worker) {
			super("Parsing " + worker.getResource().getName());
			setRule(worker.getResource());
			this.worker = worker;
		}
		
		@Override
		protected IStatus run(IProgressMonitor monitor) {
			try {
				worker.run(monitor);
			} catch (CoreException e) {
				return e.getStatus();
			}
			return Status.OK_STATUS;
		}
	}
	
	private abstract class CacheWorker implements IWorkspaceRunnable {
		private IResource resource;
		private boolean update;

		public CacheWorker(IResource resource, boolean update) {
			this.resource = resource;
			this.update = update;
		}

		public IResource getResource() {
			return resource;
		}

		public void run(IProgressMonitor monitor) throws CoreException {
			Object raw = getCached();
			if( update && raw != null || ! update && raw == null ) {
				Object model = build(resource, raw);
				resource.setSessionProperty(ONTMODEL, model);
				fireModelCached(resource);
			}
		}

		public Object getCached() throws CoreException {
			return resource.getSessionProperty(ONTMODEL);
		}
		
		protected abstract Object build(IResource resource, Object initial) throws CoreException;
	}

	private class ParseWorker extends CacheWorker {
		
		public ParseWorker(IFile file, boolean update) {
			super(file, update);
		}

		@Override
		protected Object build(IResource resource, Object initial) throws CoreException {
			return Task.parse((IFile)resource);
		}
	}
	
	private class MergeWorker extends CacheWorker {
		public MergeWorker(IFolder folder, boolean update) {
			super(folder, update);
		}

		@Override
		protected Object build(IResource resource, Object initial) throws CoreException {
			DynamicMergedModel merger = new DynamicMergedModel((IFolder)resource);
			
			if( initial != null) {
				DynamicMergedModel old = (DynamicMergedModel) initial;
				old.dispose();
			}
			
			return merger;
		}		
	}
	
	private class DynamicMergedModel implements CacheListener, IResourceVisitor {
		private OntModel model;
		private Map inputs = new HashMap();
		private IFolder folder;
		
		public DynamicMergedModel(IFolder folder) throws CoreException {
			this.folder = folder;
			if(folder.exists())
				folder.accept(this);
			addCacheListener(this);
			model = createMergedModel();
		}

		public boolean visit(IResource raw) throws CoreException {
			if( raw instanceof IFile ) {
				IFile file = (IFile) raw;
				if( isParseable(file)) {
					OntModel input = getOntologyWait(file);
					inputs.put(file, input);
				}
			}
			return true;
		}
		 
		public OntModel getBaseModel() {
			return model;
		}

		public OntModel createMergedModel() {
			MultiUnion union = new MultiUnion();
			Iterator it = inputs.values().iterator();
			while(it.hasNext()) {
				OntModel input = (OntModel) it.next();
				union.addGraph(input.getGraph());
			}
			Model basic = ModelFactory.createModelForGraph(union);
			return ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, basic);
		}

		public void modelCached(IResource raw) {
			if( ! (raw instanceof IFile))
				return;
			
			IFile key = (IFile) raw;
			
			if( ! inputs.containsKey(key))
				return;
			
			OntModel input = getOntology(key);
			if( input == null)
				return;
			
			inputs.put(key, input);
			model = createMergedModel();
			fireModelCached(folder);
		}

		public void modelDropped(IResource raw) {
			if( ! (raw instanceof IFile))
				return;
			
			IFile key = (IFile) raw;
			
			if( ! inputs.containsKey(key))
				return;
			
			inputs.remove(key);
			model = createMergedModel();
			fireModelCached(folder);
		}
		
		public void dispose() {
			removeCacheListener(this);
		}
	}

	private class ResourceListener implements IResourceChangeListener {
		public void resourceChanged(IResourceChangeEvent event) {
			if (event.getType() != IResourceChangeEvent.POST_CHANGE)
				return;

			final Set projects = new HashSet();

			try {
				event.getDelta().accept(new IResourceDeltaVisitor(){
					public boolean visit(IResourceDelta delta) {
						IResource raw = delta.getResource();
						if( raw instanceof IFile ) {
							IFile file = (IFile) raw;
							if( isParseable(file)) {
								IProject project = file.getProject();
								int kind = delta.getKind();
								if( kind == IResourceDelta.CHANGED ) {
									runJob(new ParseWorker(file, true));
								}
								else if( kind  == IResourceDelta.ADDED ) {
									projects.add(project);
								}
								else if( kind  == IResourceDelta.REMOVED ) {
									projects.add(project);
									fireModelDropped(file);
								}
							}
							else {
								IFile master = findMasterFor(file);
								if( master != null )
									runJob(new ParseWorker(master, true));
							}
							return false;
						}
						else
							return true;
					}
				});

				for (Iterator it = projects.iterator(); it.hasNext();) {
					IProject project = (IProject) it.next();
					IFolder schema = getSchemaFolder(project);
					if( schema.exists())
						runJob(new MergeWorker(schema, true));
				}

			} catch (CoreException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
