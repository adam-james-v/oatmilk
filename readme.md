# Oatmilk
Clerk Notebooks for fun.

Why *Oatmilk*?

Names are tough, and I drink lots of coffee with oat milk. 🤷‍♂️ ☕️

This is a repo where I'm playing around with *Clerk* notebooks. I want it to be public so that I can both show people what I'm trying out and have a spot where people might give feedback if they're so inclined.

I'll hopefully one day consolidate this project with my other miscellaneous and messy repos for generative art and 3D modelling, but this is better than nothing.

## Org -> Markdown
I like writing literate program notes with Org mode, and I'm really excited about Clerk, so I want to use them both. Clerk can watch for changes in .md files and render/run them no problem, they just need the src blocks to be annotated. Unfortunately, Org's bulit in .md exporter doesn't annotate code blocks.

To work around this, I installed Pandoc, which has a basic org->md exporter:

`pandoc -f org -t markdown -o your-output-file.md your-input-file.org`

And then I just shell out to this every time I save an org file in Emacs. This is in my init.el:

```elisp
(defun export-md-on-save-org-mode-file ()
  (let ((filename
        (buffer-file-name)))
    (when (and (string-match-p
                (regexp-quote ".org") (message "%s" (current-buffer)))
               (not (string-match-p
                     (regexp-quote "[") (message "%s" (current-buffer)))))
      (shell-command
       (concat "pandoc -f org -t markdown -o " filename ".md " filename)))))

(add-hook 'after-save-hook 'export-md-on-save-org-mode-file)
```
