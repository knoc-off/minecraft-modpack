package dev.assetshelf.client.gui;

import java.util.List;

/**
 * Callback for the save/edit/publish action. Receives (name, description, tags).
 */
@FunctionalInterface
public interface SaveAction {
    void accept(String name, String description, List<String> tags);
}
