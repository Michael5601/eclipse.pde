/*******************************************************************************
 *  Copyright (c) 2012, 2016 Christian Pontesegger and others.
 *
 *  This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License 2.0
 *  which accompanies this distribution, and is available at
 *  https://www.eclipse.org/legal/epl-2.0/
 *
 *  SPDX-License-Identifier: EPL-2.0
 *
 *  Contributors:
 *     Christian Pontesegger - initial API and implementation
 *     Martin Karpisek <martin.karpisek@gmail.com> - Bug 507831
 *******************************************************************************/

package org.eclipse.pde.internal.ui.views.imagebrowser.repositories;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.TargetBundle;
import org.eclipse.pde.internal.ui.PDEPlugin;
import org.eclipse.pde.internal.ui.PDEUIMessages;
import org.eclipse.pde.internal.ui.views.imagebrowser.IImageTarget;
import org.eclipse.pde.internal.ui.views.imagebrowser.ImageElement;
import org.eclipse.ui.PlatformUI;

public class TargetPlatformRepository extends AbstractRepository {

	private List<TargetBundle> fBundles = null;
	private final boolean fUseCurrent;
	private List<File> pluginFiles = new ArrayList<>();

	/**
	 * Creates a new target platform repository.  If useCurrent is <code>true</code>
	 * the current target platform set on the preference page.  If <code>false</code>
	 * a default target definition (the running application) will be used.
	 *
	 * @param target whom to notify upon found images
	 * @param useCurrent whether to use the current target platform or the default target (running application)
	 */
	public TargetPlatformRepository(IImageTarget target, boolean useCurrent) {
		super(target);

		fUseCurrent = useCurrent;
	}

	@Override
	protected boolean populateCache(final IProgressMonitor monitor) {
		if (fBundles == null) {
			initialize(monitor);
		}

		if ((fBundles != null) && (!fBundles.isEmpty())) {
			TargetBundle bundle = fBundles.remove(fBundles.size() - 1);
			URI location = bundle.getBundleInfo().getLocation();
			File file = new File(location);
			pluginFiles.add(file);
			if (isJar(file)) {
				searchJarFile(file, monitor);
			} else if (file.isDirectory()) {
				searchDirectory(file, monitor);
			}

			return true;
		}

		return false;
	}

	private void initialize(final IProgressMonitor monitor) {

		try {

			ITargetPlatformService service = PlatformUI.getWorkbench().getService(ITargetPlatformService.class);
			if (service != null) {
				ITargetDefinition fDefinition = null;
				if (fUseCurrent) {
					fDefinition = service.getWorkspaceTargetDefinition();
				} else {
					fDefinition = service.newDefaultTarget();
				}

				if (fDefinition != null) {

					if (!fDefinition.isResolved()) {
						fDefinition.resolve(monitor);
					}

					TargetBundle[] allBundles = fDefinition.getAllBundles();

					// populate bundles to visit
					if (allBundles != null) {
						fBundles = new ArrayList<>(Arrays.asList(allBundles));
					} else {
						fBundles = Collections.emptyList();
					}
				}

			} else {
				PDEPlugin.log(PDEUIMessages.TargetPlatformRepository_CouldNotFindTargetPlatformService);
			}

		} catch (CoreException e) {
			PDEPlugin.log(e);
		}
	}

	@Override
	protected synchronized IStatus run(IProgressMonitor monitor) {
		super.run(monitor);
		if (fBundles != null) {
			fBundles.clear();
			fBundles = null;
		}
		if (mElementsCache != null) {
			mElementsCache.clear();
		}
		return Status.OK_STATUS;
	}

	@Override
	public ImageElement pluginContains(String pluginFile, String fileName) {
		for (File currentPluginFile : pluginFiles) {
			if (currentPluginFile.getName().equals(pluginFile)) {
				if (isJar(currentPluginFile)) {
					return jarFileContains(currentPluginFile, fileName);

				} else if (currentPluginFile.isDirectory()) {
					return directoryContains(currentPluginFile, fileName);
				}
			}
		}
		return null;
	}

	private ImageElement jarFileContains(File pluginFile, final String fileName) {
		try (ZipFile zipFile = new ZipFile(pluginFile)) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while ((entries.hasMoreElements())) {
				ZipEntry entry = entries.nextElement();
				if (entry.getName().replace(".png", ".svg").equals(fileName)) { //$NON-NLS-1$ //$NON-NLS-2$
					return new ImageElement(() -> createImageData(pluginFile, entry), pluginFile.getName(),
							entry.getName());
				}
			}
		} catch (IOException e) {
			PDEPlugin.log(e);
		}
		return null;
	}

	private ImageElement directoryContains(File directory, final String fileName) {
		File manifest = new File(directory, "META-INF/MANIFEST.MF"); //$NON-NLS-1$
		if (manifest.exists()) {
			try {
				Optional<String> name = getPluginName(new FileInputStream(manifest));
				if (!name.isPresent()) {
					return null;
				}
				String pluginName = name.get();
				int directoryPathLength = directory.getAbsolutePath().length();

				Collection<File> locations = new HashSet<>();
				locations.add(directory);
				do {
					File next = locations.iterator().next();
					locations.remove(next);

					for (File resource : next.listFiles()) {
						if (resource.isDirectory()) {
							locations.add(resource);

						} else {
							if (isImage(resource) && resource.getName().replace(".png", ".svg").equals(fileName)) { //$NON-NLS-1$ //$NON-NLS-2$
								return new ImageElement(() -> createImageData(resource), pluginName,
										resource.getAbsolutePath().substring(directoryPathLength));
							}
						}
					}

				} while ((!locations.isEmpty()));
			} catch (IOException e) {
				// could not read manifest
				PDEPlugin.log(e);
			}
		}
		return null;
	}

	@Override
	public String toString() {
		if (!fUseCurrent) {
			return PDEUIMessages.TargetPlatformRepository_RunningPlatform;
		}

		try {
			ITargetPlatformService service = PlatformUI.getWorkbench().getService(ITargetPlatformService.class);
			if (service != null) {
				ITargetDefinition definition = service.getWorkspaceTargetDefinition();
				String name = definition.getName();
				if (name == null) {
					return ""; //$NON-NLS-1$
				}
				if (name.length() > 30) {
					name = name.substring(0, 30);
				}
				return NLS.bind(PDEUIMessages.TargetPlatformRepository_TargetPlatformLabel, name);
			}
		} catch (CoreException e) {
			PDEPlugin.log(e);
		}

		return super.toString();
	}
}
