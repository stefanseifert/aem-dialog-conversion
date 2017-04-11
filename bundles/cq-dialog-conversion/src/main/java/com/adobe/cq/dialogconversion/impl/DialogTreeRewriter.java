/*************************************************************************
 *
 * ADOBE CONFIDENTIAL
 * __________________
 *
 *  Copyright 2014 Adobe Systems Incorporated
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Adobe Systems Incorporated and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Adobe Systems Incorporated and its
 * suppliers and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Adobe Systems Incorporated.
 **************************************************************************/

package com.adobe.cq.dialogconversion.impl;

import com.adobe.cq.dialogconversion.DialogRewriteException;
import com.adobe.cq.dialogconversion.DialogRewriteRule;
import com.adobe.cq.dialogconversion.DialogRewriteUtils;
import com.adobe.cq.dialogconversion.DialogType;
import com.day.cq.commons.jcr.JcrUtil;
import org.apache.jackrabbit.commons.flat.TreeTraverser;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DialogTreeRewriter {

    private Logger logger = LoggerFactory.getLogger(DialogTreeRewriter.class);

    private List<DialogRewriteRule> rules;

    private static final String NN_CQ_DIALOG = "cq:dialog";
    private static final String NN_DIALOG = "dialog";
    private static final String BACKUP_SUFFIX = ".coral2";

    public DialogTreeRewriter(List<DialogRewriteRule> rules) {
        this.rules = rules;
    }

    private void check(Node root)
            throws DialogRewriteException, RepositoryException {
        // if it's not a classic or coral 2 dialog, throw an exception
        DialogType type = DialogRewriteUtils.getDialogType(root);

        // verify that the node is a dialog and is convertible
        if (type == DialogType.UNKNOWN || type == DialogType.CORAL_3) {
            logger.debug("{} is not a Classic or Coral 2 dialog", root.getPath());
            throw new DialogRewriteException("Node is not a cq:Dialog or Coral 2 dialog");
        }

        if (type == DialogType.CLASSIC) {
            // verify that a Coral 3 version of the dialog doesn't already exist
            if (root.getParent().hasNode(NN_CQ_DIALOG)) {
                Node node = root.getParent().getNode(NN_CQ_DIALOG);
                type = DialogRewriteUtils.getDialogType(node);
                if (type == DialogType.CORAL_3) {
                    logger.debug("Dialog {} already has a Coral 3 counterpart", root.getPath());
                    throw new DialogRewriteException("Coral 3 dialog already exists");
                }
            }
        }
    }

    /**
     * Rewrites the specified dialog tree according to the set of rules passed to the constructor.
     *
     * @param root The root of the dialog be rewritten
     * @return the root node of the rewritten dialog tree, or null if it was removed
     * @throws com.adobe.cq.dialogconversion.DialogRewriteException If the rewrite operation fails
     * @throws RepositoryException If there is a problem with the repository
     */
    public Node rewrite(Node root)
            throws DialogRewriteException, RepositoryException {
        logger.debug("Rewriting dialog tree rooted at {}", root.getPath());
        check(root);

        DialogType type = DialogRewriteUtils.getDialogType(root);
        String name = "";

        if (type == DialogType.CORAL_2) {
            name = NN_CQ_DIALOG + BACKUP_SUFFIX;
            Node parent = root.getParent();
            if (parent != null) {
                if (parent.hasNode(name)) {
                    // remove any existing backup node
                    parent.getNode(name).remove();
                }
            }
        } else {
            name = JcrUtil.createValidChildName(root.getParent(), NN_DIALOG);
        }

        // make a copy of the dialog. If the dialog is Classic, the copy will be rewritten.
        // Otherwise, it serves as a backup.
        Node copy = JcrUtil.copy(root, root.getParent(), name);

        /**
         * Description of the algorithm:
         * - traverse the tree rooted at 'root' in pre-order
         * - for each node we check if one of the rules match
         * - on a match, the (sub)tree rooted at that node is rewritten according to that rule,
         *   and we restart the traversal from 'root'
         * - the algorithm stops when the whole tree has been traversed and no node has matched any rule
         * - some special care has to be taken to keep the orderings of child nodes when rewriting subtrees
         */

        long tick = System.currentTimeMillis();
        Session session = root.getSession();
        // reference to the node where the pre-order traversal is started from. If the dialog is
        // Coral 2 start from the root, leaving the backup copy
        Node startNode = type == DialogType.CORAL_2 ? root : copy;
        // keeps track of whether or not there was a match during a traversal
        boolean foundMatch;
        // keeps track of whether or not the rewrite operation succeeded
        boolean success = false;
        // collect paths of nodes that are final and can be skipped by the algorithm
        Set<String> finalPaths = new LinkedHashSet<String>();

        try {
            // do a pre-order tree traversal until we found no match
            do {
                foundMatch = false;
                TreeTraverser traverser = new TreeTraverser(startNode);
                Iterator<Node> iterator = traverser.iterator();
                logger.debug("Starting new pre-order tree traversal");
                // traverse the tree in pre-order
                while (iterator.hasNext()) {
                    Node node = iterator.next();

                    // if this node and its siblings are ordered..
                    if (node.getParent().getPrimaryNodeType().hasOrderableChildNodes()) {
                        // ..then we move it to the end of its parent's list of children. This is necessary because
                        // any of its siblings might be rewritten (which might move it to the end of the list). Thus
                        // we do this for all siblings in order to keep the order.
                        node.getParent().orderBefore(node.getName(), null);
                    }

                    // we have previously found a match (and will start a new traversal from the start node)
                    // but we still need to finish this traversal in order not to change the order of nodes
                    if (foundMatch) {
                        continue;
                    }

                    // check if we should skip this node
                    if (finalPaths.contains(node.getPath())) {
                        continue;
                    }

                    // traverse all available rules
                    Set<Node> finalNodes = new LinkedHashSet<Node>();
                    for (DialogRewriteRule rule : rules) {
                        // check for a match
                        if (rule.matches(node)) {
                            logger.debug("Rule {} matched subtree rooted at {}", rule, node.getPath());
                            // the rule matched, rewrite the tree
                            Node result = rule.applyTo(node, finalNodes);
                            // set the start node in case it was rewritten
                            if (node.equals(startNode)) {
                                startNode = result;
                            }
                            addPaths(finalPaths, finalNodes);
                            foundMatch = true;
                            break;
                        }
                    }

                    // if we have found no match for this node, we can ignore it
                    // in subsequent traversals
                    if (!foundMatch) {
                        finalNodes.add(node);
                        addPaths(finalPaths, finalNodes);
                    }
                }
            } while (foundMatch && startNode != null);
            success = true;
        } finally {
            if (!success) {
                // an exception has been thrown: try to revert changes
                try {
                    session.refresh(false);
                } catch (RepositoryException e) {
                    logger.warn("Could not revert changes", e);
                }
            }
        }

        // save changes
        session.save();

        long tack = System.currentTimeMillis();
        logger.debug("Rewrote dialog tree rooted at {} in {} ms", root.getPath(), tack - tick);

        return startNode;
    }

    private void addPaths(Set<String> paths, Set<Node> nodes)
            throws RepositoryException {
        for (Node node : nodes) {
            paths.add(node.getPath());
        }
    }
}
